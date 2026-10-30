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

    public String createdAt;
    public String updatedAt;

    public ResearchTask() {
    }
}
