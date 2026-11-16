package com.cosme.service;

/**
 * 未收录成分的研究任务。
 *
 * 分析阶段先把 token 写进队列，不再依赖「AI 调用成功才留痕」；
 * 后台 worker 依次研究，成功写库，失败保留错误并可重试。
 */
public class ResearchTask {

    public static final String QUEUED = "queued";
    public static final String RESEARCHING = "researching";
    /** 研究完成但需要人工确认：草稿已生成，尚未写入成分库 */
    public static final String REVIEW = "review";
    public static final String DONE = "done";
    public static final String FAILED = "failed";

    public String id;
    public String query;
    public String context;
    public String source = "auto";

    public String status = QUEUED;
    public int attempts;
    public long nextAttemptAt;
    public String error;

    public String resultName;
    public Double confidence;
    public String model;
    public String evidence;

    /** Agent 的执行计划与每一步的工具调用轨迹（管理台可展开） */
    public java.util.List<String> plan;
    public java.util.List<ResearchAgent.Step> trace;

    /** 待复核的草稿；approve 时写库，reject 时丢弃 */
    public IngredientAdminService.Draft draft;
    /** 为什么进人工：字段不合法 / 置信度不足 */
    public String reason;

    public String createdAt;
    public String updatedAt;

    public ResearchTask() {
    }
}
