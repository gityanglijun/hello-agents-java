package com.cybertown.model;

import java.util.*;

/**
 * NPC 数据模型 — 对应架构中"NPC状态"
 */
public class Npc {

    private String id;
    private String name;
    private String title;
    private String location;
    private String activity;
    private String personality;
    private double x;
    private double y;

    public Npc() {}

    public Npc(String name, String title, String location, String activity, String personality) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.title = title;
        this.location = location;
        this.activity = activity;
        this.personality = personality;
    }

    public static Npc fromConfig(String name, com.cybertown.agent.NpcConfig.NpcRole role) {
        Npc npc = new Npc(name, role.title, role.location, role.activity, role.personality);
        npc.id = "npc_" + name;
        return npc;
    }

    // ========== getters/setters ==========

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public String getActivity() { return activity; }
    public void setActivity(String activity) { this.activity = activity; }

    public String getPersonality() { return personality; }
    public void setPersonality(String personality) { this.personality = personality; }

    public double getX() { return x; }
    public void setX(double x) { this.x = x; }

    public double getY() { return y; }
    public void setY(double y) { this.y = y; }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("title", title);
        map.put("location", location);
        map.put("activity", activity);
        map.put("x", x);
        map.put("y", y);
        return map;
    }
}
