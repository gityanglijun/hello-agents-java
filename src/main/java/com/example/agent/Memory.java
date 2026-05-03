package com.example.agent;
import java.util.*;

public class Memory {

    List<Map<String, Object>> records;
    public Memory(){
        this.records = new ArrayList<>();
    }

    public void addRecord(String recordType,String content){
        /*
        向记忆中添加一条新记录。

        参数:
        - record_type (str): 记录的类型 ('execution' 或 'reflection')。
        - content (str): 记录的具体内容 (例如，生成的代码或反思的反馈)。
        */
        Map<String,Object> record = new HashMap<>();
        record.put("type",recordType);
        record.put("content",content);
        records.add(record);
        System.out.println("📝 记忆已更新，新增一条 '"+recordType+"' 记录。");
    }

    public String getTrajectory(){
        /*
        将所有记忆记录格式化为一个连贯的字符串文本，用于构建提示词。
        */

        String trajectory = "";
        for(Map<String,Object> record : records){
            if(record.get("type").equals("execution")){
                trajectory += "--- 上一轮尝试 (代码) ---\\n"+record.get("content")+"\\n\\n";
            }
            else if(record.get("type").equals("reflection")){
                trajectory += "--- 评审员反馈 ---\n"+record.get("content")+"\n\n";
            }
        }
        return trajectory.strip();
    }

    public String getLastExecution(){
        /*
        获取最近一次的执行结果 (例如，最新生成的代码)。
        */
        for (int i = records.size() - 1; i >= 0; i--) {
            Map<String, Object> record = records.get(i);
            if ("execution".equals(record.get("type"))) {
                return record.get("content").toString();
            }
        }
        return null;
    }
}
