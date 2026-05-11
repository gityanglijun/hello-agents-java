#!/usr/bin/env python3
"""
将 BFCL v3 JSONL 数据转换为 v4 兼容格式（生成 ground_truth）。

BFCL v3 数据包含 id/question/function 但没有 ground_truth。
此脚本使用 LLM 为每个问题生成期望的函数调用，输出 v4 兼容的 JSON。

使用:
  export HF_TOKEN="hf_xxx"   # 可选，下载 v3 数据时使用
  export OPENAI_API_KEY="sk-xxx"
  python generate_bfcl_v4_groundtruth.py --category simple --max-samples 10

输出: evaluation_data/BFCL_v4_simple_from_v3.json
"""

import argparse
import json
import os
import sys
import time
from pathlib import Path

# ==================== 配置 ====================

# 如果 v3 文件不在本地，从这里下载
V3_DOWNLOAD_URLS = {
    "simple": "https://hf-mirror.com/datasets/gorilla-llm/Berkeley-Function-Calling-Leaderboard/resolve/main/BFCL_v3_simple.json",
    "multiple": "https://hf-mirror.com/datasets/gorilla-llm/Berkeley-Function-Calling-Leaderboard/resolve/main/BFCL_v3_multiple.json",
    "parallel": "https://hf-mirror.com/datasets/gorilla-llm/Berkeley-Function-Calling-Leaderboard/resolve/main/BFCL_v3_parallel.json",
    "parallel_multiple": "https://hf-mirror.com/datasets/gorilla-llm/Berkeley-Function-Calling-Leaderboard/resolve/main/BFCL_v3_parallel_multiple.json",
    "irrelevance": "https://hf-mirror.com/datasets/gorilla-llm/Berkeley-Function-Calling-Leaderboard/resolve/main/BFCL_v3_irrelevance.json",
    "java": "https://hf-mirror.com/datasets/gorilla-llm/Berkeley-Function-Calling-Leaderboard/resolve/main/BFCL_v3_java.json",
    "javascript": "https://hf-mirror.com/datasets/gorilla-llm/Berkeley-Function-Calling-Leaderboard/resolve/main/BFCL_v3_javascript.json",
}

SYSTEM_PROMPT = """You are a function calling assistant. Given a user question and available functions, output the correct function call.

Output ONLY a JSON object (no explanation):
{"name": "<function_name>", "arguments": {<argument_dict>}}

If NO function should be called, output:
{"name": "", "arguments": {}}"""


def load_v3_jsonl(filepath: str) -> list[dict]:
    """加载 BFCL v3 JSONL 文件。"""
    samples = []
    with open(filepath, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if line:
                samples.append(json.loads(line))
    return samples


def build_messages(sample: dict) -> tuple[str, list[dict]]:
    """从 v3 sample 提取 question 文本和 function 列表。"""
    # 提取 user question
    question = ""
    q_data = sample.get("question", [])
    if isinstance(q_data, list):
        for turn in q_data:
            if isinstance(turn, list):
                for msg in turn:
                    if isinstance(msg, dict) and msg.get("role") == "user":
                        question = msg.get("content", "")
                        break

    # 提取 functions
    functions = sample.get("function", [])

    return question, functions


def format_functions_for_prompt(functions: list[dict]) -> str:
    """将函数列表格式化为可读的提示词。"""
    lines = []
    for func in functions:
        name = func.get("name", "unknown")
        desc = func.get("description", "")
        params = func.get("parameters", {})
        properties = params.get("properties", {})
        required = params.get("required", [])

        param_strs = []
        for pname, pinfo in properties.items():
            ptype = pinfo.get("type", "string")
            pdesc = pinfo.get("description", "")
            req = " (required)" if pname in required else ""
            param_strs.append(f"    {pname}: {ptype} — {pdesc}{req}")

        lines.append(f"## {name}\n{desc}\nParameters:\n" + "\n".join(param_strs))

    return "\n\n".join(lines)


def call_llm(question: str, functions: list[dict], model: str = "deepseek-v4-flash") -> dict:
    """调用 LLM 生成 ground_truth function call。"""
    import requests

    api_key = os.environ.get("OPENAI_API_KEY", "")
    base_url = os.environ.get("OPENAI_BASE_URL", "https://api.openai.com/v1")

    func_text = format_functions_for_prompt(functions)

    messages = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": f"Available functions:\n\n{func_text}\n\nUser question: {question}"}
    ]

    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json"
    }

    payload = {
        "model": model,
        "messages": messages,
        "temperature": 0.0,
        "max_tokens": 500
    }

    resp = requests.post(
        f"{base_url}/chat/completions",
        headers=headers,
        json=payload,
        timeout=30
    )

    if resp.status_code != 200:
        raise Exception(f"API error {resp.status_code}: {resp.text}")

    content = resp.json()["choices"][0]["message"]["content"].strip()

    # 提取 JSON
    content = content.strip()
    if content.startswith("```"):
        lines = content.split("\n")
        content = "\n".join(lines[1:])
        if content.endswith("```"):
            content = content[:-3]

    return json.loads(content)


def convert_v3_to_v4(
    category: str,
    max_samples: int = 0,
    model: str = "deepseek-v4-flash",
    delay: float = 1.0
) -> str:
    """将 v3 JSONL 转换为 v4 JSON 格式。"""
    eval_dir = Path(__file__).parent.parent / "evaluation_data"
    v3_file = eval_dir / f"BFCL_v3_{category}.json"

    # 如果 v3 文件不在本地，从镜像下载
    if not v3_file.exists():
        print(f"v3 文件不在本地，从 hf-mirror 下载...")
        url = V3_DOWNLOAD_URLS.get(category)
        if not url:
            print(f"  错误: 不支持的类别 '{category}'")
            print(f"  可用类别: {list(V3_DOWNLOAD_URLS.keys())}")
            sys.exit(1)

        import urllib.request
        urllib.request.urlretrieve(url, v3_file)
        print(f"  OK: 已下载: {v3_file}")

    # 加载
    samples = load_v3_jsonl(str(v3_file))
    print(f"OK: 加载了 {len(samples)} 条 v3 样本")

    if max_samples > 0:
        samples = samples[:max_samples]

    # 转换
    results = []
    for i, sample in enumerate(samples):
        qid = sample.get("id", f"{category}_{i}")
        question, functions = sample.get("question"), sample.get("function", [])

        # 对于 irrelevance 类别，直接生成空 ground_truth
        if category == "irrelevance":
            gt = {"name": "", "arguments": {}}
        else:
            print(f"  [{i+1}/{len(samples)}] {qid} ...", end=" ", flush=True)
            try:
                # 提取问题文本
                q_text = ""
                if isinstance(question, list):
                    for turn in question:
                        if isinstance(turn, list):
                            for msg in turn:
                                if isinstance(msg, dict) and msg.get("role") == "user":
                                    q_text = msg.get("content", "")
                gt = call_llm(q_text, functions, model)
                print("OK:")
            except Exception as e:
                print(f"ERR: {e}")
                gt = {"name": "", "arguments": {}}

            if delay > 0:
                time.sleep(delay)

        results.append({
            "id": qid,
            "question": question,
            "function": functions,
            "ground_truth": gt
        })

    # 保存
    output_file = eval_dir / f"BFCL_v4_{category}_from_v3.json"
    with open(output_file, 'w', encoding='utf-8') as f:
        json.dump(results, f, indent=2, ensure_ascii=False)

    print(f"\nOK: 已生成 {len(results)} 条 v4 格式数据 → {output_file}")
    return str(output_file)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="将 BFCL v3 数据转换为 v4 格式")
    parser.add_argument("--category", default="simple",
                        help="评估类别 (simple/multiple/parallel/parallel_multiple/irrelevance/java/javascript)")
    parser.add_argument("--max-samples", type=int, default=10,
                        help="最大样本数 (0=全部)")
    parser.add_argument("--model", default="deepseek-v4-flash",
                        help="用于生成 ground_truth 的 LLM 模型")
    parser.add_argument("--delay", type=float, default=1.0,
                        help="请求间隔 (秒)")
    args = parser.parse_args()

    print("=" * 60)
    print("BFCL v3 → v4 格式转换")
    print("=" * 60)
    print(f"  类别: {args.category}")
    print(f"  样本数: {args.max_samples if args.max_samples > 0 else '全部'}")
    print(f"  模型: {args.model}")

    convert_v3_to_v4(args.category, args.max_samples, args.model, args.delay)
