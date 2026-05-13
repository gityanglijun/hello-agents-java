package com.example.agent.deepresearch.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 深度研究助手数据模型，对应 Python deepresearch/models.py。
 */
public final class DeepResearchModels {

    /** 单个待办任务项，对应 Python TodoItem dataclass。 */
    public static class TodoItem {
        private int id;
        private String title;
        private String intent;
        private String query;
        private String status = "pending";
        private String summary;
        private String sourcesSummary;
        private List<String> notices = new ArrayList<>();
        private String noteId;
        private String notePath;
        private String streamToken;

        public TodoItem() {}
        public TodoItem(int id, String title, String intent, String query) {
            this.id = id;
            this.title = title;
            this.intent = intent;
            this.query = query;
        }

        // Getters & setters
        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getIntent() { return intent; }
        public void setIntent(String intent) { this.intent = intent; }
        public String getQuery() { return query; }
        public void setQuery(String query) { this.query = query; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
        public String getSourcesSummary() { return sourcesSummary; }
        public void setSourcesSummary(String s) { this.sourcesSummary = s; }
        public List<String> getNotices() { return notices; }
        public void setNotices(List<String> notices) { this.notices = notices; }
        public String getNoteId() { return noteId; }
        public void setNoteId(String noteId) { this.noteId = noteId; }
        public String getNotePath() { return notePath; }
        public void setNotePath(String notePath) { this.notePath = notePath; }
        public String getStreamToken() { return streamToken; }
        public void setStreamToken(String t) { this.streamToken = t; }
    }

    /** 研究状态，对应 Python SummaryState dataclass。 */
    public static class SummaryState {
        private String researchTopic;
        private List<String> webResearchResults = new ArrayList<>();
        private List<String> sourcesGathered = new ArrayList<>();
        private int researchLoopCount;
        private String runningSummary;
        private List<TodoItem> todoItems = new ArrayList<>();
        private String structuredReport;
        private String reportNoteId;
        private String reportNotePath;

        public SummaryState() {}
        public SummaryState(String researchTopic) {
            this.researchTopic = researchTopic;
        }

        public String getResearchTopic() { return researchTopic; }
        public void setResearchTopic(String t) { this.researchTopic = t; }
        public List<String> getWebResearchResults() { return webResearchResults; }
        public List<String> getSourcesGathered() { return sourcesGathered; }
        public int getResearchLoopCount() { return researchLoopCount; }
        public void setResearchLoopCount(int n) { this.researchLoopCount = n; }
        public String getRunningSummary() { return runningSummary; }
        public void setRunningSummary(String s) { this.runningSummary = s; }
        public List<TodoItem> getTodoItems() { return todoItems; }
        public void setTodoItems(List<TodoItem> items) { this.todoItems = items; }
        public String getStructuredReport() { return structuredReport; }
        public void setStructuredReport(String r) { this.structuredReport = r; }
        public String getReportNoteId() { return reportNoteId; }
        public void setReportNoteId(String id) { this.reportNoteId = id; }
        public String getReportNotePath() { return reportNotePath; }
        public void setReportNotePath(String p) { this.reportNotePath = p; }
    }

    /** HTTP 请求 */
    public static class ResearchRequest {
        private String topic;
        private String searchApi;  // 可选，覆盖配置

        public String getTopic() { return topic; }
        public void setTopic(String topic) { this.topic = topic; }
        public String getSearchApi() { return searchApi; }
        public void setSearchApi(String api) { this.searchApi = api; }
    }

    /** 研究输出结果 */
    public static class SummaryStateOutput {
        private String runningSummary;
        private String reportMarkdown;
        private List<TodoItem> todoItems = new ArrayList<>();

        public String getRunningSummary() { return runningSummary; }
        public void setRunningSummary(String s) { this.runningSummary = s; }
        public String getReportMarkdown() { return reportMarkdown; }
        public void setReportMarkdown(String s) { this.reportMarkdown = s; }
        public List<TodoItem> getTodoItems() { return todoItems; }
        public void setTodoItems(List<TodoItem> items) { this.todoItems = items; }
    }

    /** HTTP 响应 */
    public static class ResearchResponse {
        private boolean success;
        private String reportMarkdown;
        private List<TodoItem> todoItems = new ArrayList<>();

        public static ResearchResponse ok(String report, List<TodoItem> items) {
            ResearchResponse r = new ResearchResponse();
            r.success = true;
            r.reportMarkdown = report;
            r.todoItems = items;
            return r;
        }

        public static ResearchResponse error(String msg) {
            ResearchResponse r = new ResearchResponse();
            r.success = false;
            r.reportMarkdown = msg;
            return r;
        }

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean s) { this.success = s; }
        public String getReportMarkdown() { return reportMarkdown; }
        public void setReportMarkdown(String m) { this.reportMarkdown = m; }
        public List<TodoItem> getTodoItems() { return todoItems; }
        public void setTodoItems(List<TodoItem> items) { this.todoItems = items; }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("success", success);
            map.put("report_markdown", reportMarkdown);
            List<Map<String, Object>> tasks = new ArrayList<>();
            for (TodoItem t : todoItems) {
                Map<String, Object> tm = new java.util.LinkedHashMap<>();
                tm.put("id", t.getId());
                tm.put("title", t.getTitle());
                tm.put("intent", t.getIntent());
                tm.put("query", t.getQuery());
                tm.put("status", t.getStatus());
                tm.put("summary", t.getSummary());
                tm.put("sources_summary", t.getSourcesSummary());
                tasks.add(tm);
            }
            map.put("todo_items", tasks);
            return map;
        }
    }
}
