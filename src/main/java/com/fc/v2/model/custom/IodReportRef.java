package com.fc.v2.model.custom;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 一份已落档报告上挂着的"线代号 + 问话时刻"，用于回线册核对那一档当时能否请出
 * 在场的线（补算往期顶替旧错的复查入口）。
 *
 * @author fuce
 * @date 2026-10-02
 */
public class IodReportRef {

    /** 送检报告/批验单代号 */
    private String bizNo;

    /** 结论上挂着的线代号 */
    private String ruleCode;

    /** 该报告自己的问话时刻（取样/判档那一刻，不是眼下） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date at;

    public IodReportRef() {
    }

    public IodReportRef(String bizNo, String ruleCode, Date at) {
        this.bizNo = bizNo;
        this.ruleCode = ruleCode;
        this.at = at;
    }

    public String getBizNo() {
        return bizNo;
    }

    public void setBizNo(String bizNo) {
        this.bizNo = bizNo;
    }

    public String getRuleCode() {
        return ruleCode;
    }

    public void setRuleCode(String ruleCode) {
        this.ruleCode = ruleCode;
    }

    public Date getAt() {
        return at;
    }

    public void setAt(Date at) {
        this.at = at;
    }
}
