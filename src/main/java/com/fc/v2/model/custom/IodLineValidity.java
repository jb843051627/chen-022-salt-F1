package com.fc.v2.model.custom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一条碘含量分档判定线的立线校验回执。
 *
 * 三个分界数（起算 th1Max / 够线 th2Max / 封顶 th3Max）缺一个、或后一个没有严格越过
 * 前一个，这条线就立不起来；立线之日缺失同样立不起来。
 * 回执不许只讲"数值不对劲"：errors 逐句指名哪一栏空着、哪一栏没越过前头那一栏，
 * fieldErrors 按栏号(th1Max/th2Max/th3Max/effStart)给出该栏的话，供屏上逐栏标红。
 *
 * @author fuce
 * @date 2026-10-02
 */
public class IodLineValidity {

    /** 是否立得住 */
    private boolean valid;

    /** 逐句指名的毛病（空着/没越过），立得住时为空表 */
    private List<String> errors = new ArrayList<String>();

    /** 栏号 -> 该栏的毛病，屏上逐栏回执用 */
    private Map<String, String> fieldErrors = new LinkedHashMap<String, String>();

    public boolean isValid() {
        return valid;
    }

    public void setValid(boolean valid) {
        this.valid = valid;
    }

    public List<String> getErrors() {
        return errors;
    }

    public void setErrors(List<String> errors) {
        this.errors = errors;
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }

    public void setFieldErrors(Map<String, String> fieldErrors) {
        this.fieldErrors = fieldErrors;
    }
}
