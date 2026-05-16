# ============================================================
# API 客户端 — Godot 前端与 Java 后端通信的唯一桥梁
# ============================================================
# 【核心知识点】
#
# 1. Godot 信号系统 (signal)
#    信号 = 观察者模式的内置实现。工作流程：
#     ① 声明 signal xxx(args)           ← 定义"会发生什么事情"
#     ② xxx.emit(args)                  ← API 回调中发射信号
#     ③ target.xxx.connect(_on_xxx)     ← 感兴趣的节点注册监听
#    效果：HTTP 回调不需要知道"谁要这个数据"，只管 emit，
#          需要数据的节点自己 connect，完全解耦。
#
# 2. HTTPRequest 节点
#    - request() 是异步的，调用后立即返回 Error 码
#    - 真正的响应在 request_completed 信号的回调中处理
#    - 每个端点独立一个 HTTPRequest，防止并发请求互相覆盖
#
# 3. JSON 数据流
#    发送端: Dictionary → JSON.stringify() → HTTP body (字符串)
#    接收端: HTTP body → JSON.parse() → Dictionary
#    注意 parse() 返回的是 Error 码，真正的数据在 json.data
#
# 4. 三层错误处理
#    Layer 1: HTTP 状态码 (response_code != 200)
#    Layer 2: JSON 解析 (parse_result != OK)
#    Layer 3: 业务字段 (response["success"] != true)
# ============================================================
extends Node

# ==================== 信号 ====================
signal chat_response_received(npc_name: String, message: String)
signal chat_error(error_message: String)
signal npc_status_received(dialogues: Dictionary)
signal npc_list_received(npcs: Array)

# ==================== HTTP 节点 ====================
var http_chat: HTTPRequest
var http_status: HTTPRequest
var http_npcs: HTTPRequest

func _ready():
	http_chat = HTTPRequest.new()
	http_status = HTTPRequest.new()
	http_npcs = HTTPRequest.new()
	add_child(http_chat)
	add_child(http_status)
	add_child(http_npcs)

	# request_completed 是 HTTPRequest 的内置信号
	# 签名: (result: int, response_code: int, headers: PackedStringArray, body: PackedByteArray)
	http_chat.request_completed.connect(_on_chat_request_completed)
	http_status.request_completed.connect(_on_status_request_completed)
	http_npcs.request_completed.connect(_on_npcs_request_completed)

	print("[INFO] API客户端初始化完成 (后端: %s)" % Config.API_BASE_URL)

# ==================== POST /chat ====================

func send_chat(npc_name: String, message: String) -> void:
	var data = {"npc_name": npc_name, "message": message}
	var json_string = JSON.stringify(data)
	var headers = ["Content-Type: application/json"]

	print("[API] POST /chat -> ", data)

	# request() 立即返回，不阻塞游戏循环。HTTP 响应到达后触发 request_completed 信号
	var error = http_chat.request(Config.API_CHAT, headers, HTTPClient.METHOD_POST, json_string)
	if error != OK:
		print("[ERROR] 发送对话请求失败: ", error)
		chat_error.emit("网络请求失败")

func _on_chat_request_completed(_result: int, response_code: int,
		_headers: PackedStringArray, body: PackedByteArray) -> void:
	# Layer 1: 检查 HTTP 状态码
	if response_code != 200:
		print("[ERROR] 对话请求失败: HTTP ", response_code)
		chat_error.emit("服务器错误: " + str(response_code))
		return

	# Layer 2: 解析 JSON
	var json = JSON.new()
	var parse_result = json.parse(body.get_string_from_utf8())
	if parse_result != OK:
		print("[ERROR] 解析响应失败")
		chat_error.emit("响应解析失败")
		return

	var response = json.data

	# Layer 3: 检查业务 success 字段
	if response.has("success") and response["success"]:
		chat_response_received.emit(response["npc_name"], response["message"])
	else:
		chat_error.emit("对话失败")

# ==================== GET /npcs/status ====================

func get_npc_status() -> void:
	# 防抖：避免前端轮询快于后端响应时堆积请求
	if http_status.get_http_client_status() != HTTPClient.STATUS_DISCONNECTED:
		print("[WARN] NPC状态请求正在处理中,跳过本次请求")
		return

	print("[API] GET /npcs/status")
	var error = http_status.request(Config.API_NPC_STATUS)
	if error != OK:
		print("[ERROR] 获取NPC状态失败: ", error)

func _on_status_request_completed(_result: int, response_code: int,
		_headers: PackedStringArray, body: PackedByteArray) -> void:
	if response_code != 200:
		print("[ERROR] NPC状态请求失败: HTTP ", response_code)
		return

	var json = JSON.new()
	if json.parse(body.get_string_from_utf8()) != OK:
		print("[ERROR] 解析NPC状态失败")
		return

	var response = json.data
	if response.has("dialogues"):
		print("[INFO] 收到NPC状态更新: ", response["dialogues"].size(), "个NPC")
		npc_status_received.emit(response["dialogues"])

# ==================== GET /npcs ====================

func get_npc_list() -> void:
	print("[API] GET /npcs")
	var error = http_npcs.request(Config.API_NPCS)
	if error != OK:
		print("[ERROR] 获取NPC列表失败: ", error)

func _on_npcs_request_completed(_result: int, response_code: int,
		_headers: PackedStringArray, body: PackedByteArray) -> void:
	if response_code != 200:
		print("[ERROR] NPC列表请求失败: HTTP ", response_code)
		return

	var json = JSON.new()
	if json.parse(body.get_string_from_utf8()) != OK:
		print("[ERROR] 解析NPC列表失败")
		return

	var response = json.data
	if response.has("npcs"):
		print("[INFO] 收到NPC列表: ", response["npcs"].size(), "个NPC")
		npc_list_received.emit(response["npcs"])
