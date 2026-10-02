package com.fc.v2.controller.admin;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.common.log.Log;
import com.fc.v2.model.auto.TSaltIodLine;
import com.fc.v2.model.custom.IodLineVerdict;
import com.fc.v2.service.ITSaltIodLineService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 碘含量分档判定线（线册）Controller。
 *
 * 屏上只有一套说法：在场名单、定档、按代号翻检、悬空甄别同走 service 的在场口径；
 * 已停用的线册里翻得着、全册列得出，只是不参与定档。
 *
 * @author fuce
 * @date 2026-10-02
 */
@Api(value = "碘含量分档判定线")
@Controller
@RequestMapping("/saltIodLine")
public class SaltIodLineController extends BaseController {

    private final String prefix = "admin/saltIodLine";

    @Autowired
    private ITSaltIodLineService saltIodLineService;

    @ApiOperation(value = "线册台账跳转", notes = "线册台账跳转")
    @GetMapping("/view")
    @RequiresPermissions("salt:saltIodLine:view")
    public String view(ModelMap model) {
        return prefix + "/list";
    }

    @Log(title = "碘含量判定线册全册", action = "list")
    @ApiOperation(value = "线册全册", notes = "逐条列得出，含已停用；只剔除已删除")
    @GetMapping("/book")
    @RequiresPermissions("salt:saltIodLine:list")
    @ResponseBody
    public AjaxResult book() {
        List<TSaltIodLine> book = saltIodLineService.listBook();
        AjaxResult result = AjaxResult.success();
        result.put("data", book);
        result.put("total", book.size());
        return result;
    }

    @Log(title = "碘含量判定在场名单", action = "available")
    @ApiOperation(value = "在场名单", notes = "at 那一刻立线之日已够着、让位之日还没到的线，按说话次序排好")
    @GetMapping("/available")
    @RequiresPermissions("salt:saltIodLine:list")
    @ResponseBody
    public AjaxResult available(@RequestParam("at") String atText) {
        Date at = parseAt(atText);
        if (at == null) {
            return AjaxResult.error("问话那一刻(at)空着或格式不对，须为 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss");
        }
        List<TSaltIodLine> present = saltIodLineService.listAvailable(at);
        AjaxResult result = AjaxResult.success();
        result.put("data", present);
        result.put("total", present.size());
        result.put("at", atText);
        return result;
    }

    @ApiOperation(value = "在场条数", notes = "角标用，口径与在场名单同一处")
    @GetMapping("/count")
    @RequiresPermissions("salt:saltIodLine:list")
    @ResponseBody
    public AjaxResult count(@RequestParam("at") String atText) {
        Date at = parseAt(atText);
        if (at == null) {
            return AjaxResult.error("问话那一刻(at)空着或格式不对，须为 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss");
        }
        AjaxResult result = AjaxResult.success();
        result.put("data", saltIodLineService.countAvailable(at));
        return result;
    }

    @ApiOperation(value = "按代号请在场线", notes = "代号在册但那一刻不在线（含已停用）回 null，不拿别的线顶替")
    @GetMapping("/resolve")
    @RequiresPermissions("salt:saltIodLine:list")
    @ResponseBody
    public AjaxResult resolve(@RequestParam("ruleCode") String ruleCode,
                              @RequestParam("at") String atText) {
        Date at = parseAt(atText);
        if (at == null) {
            return AjaxResult.error("问话那一刻(at)空着或格式不对，须为 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss");
        }
        TSaltIodLine line = saltIodLineService.resolveByCode(ruleCode, at);
        AjaxResult result = AjaxResult.success();
        result.put("data", line);
        result.put("present", line != null);
        return result;
    }

    @Log(title = "送检报告碘含量定档", action = "judge")
    @ApiOperation(value = "定档", notes = "取 at 那一刻在场且说话的唯一一条比数；带 ruleCode 时只请该代号，"
            + "请不出按无可用线回话。取样、封样须同用一条线、同一个 at")
    @GetMapping("/judge")
    @RequiresPermissions("salt:saltIodLine:judge")
    @ResponseBody
    public AjaxResult judge(@RequestParam(value = "ruleCode", required = false) String ruleCode,
                            @RequestParam("iodine") String iodineText,
                            @RequestParam("at") String atText) {
        Date at = parseAt(atText);
        if (at == null) {
            return AjaxResult.error("问话那一刻(at)空着或格式不对，须为 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss");
        }
        java.math.BigDecimal iodine;
        try {
            iodine = new java.math.BigDecimal(iodineText.trim());
        } catch (RuntimeException e) {
            return AjaxResult.error("碘量(iodine)一栏空着或不是数，没法比数");
        }
        IodLineVerdict verdict = (ruleCode == null || ruleCode.trim().isEmpty())
                ? saltIodLineService.judge(iodine, at)
                : saltIodLineService.judgeByCode(ruleCode.trim(), iodine, at);
        AjaxResult result = AjaxResult.success();
        result.put("data", verdict);
        return result;
    }

    @Log(title = "悬空线代号甄别", action = "dangling")
    @ApiOperation(value = "悬空单逐条指出", notes = "入参 [{billNo, ruleCode, at}]，"
            + "回屏上挂着代号、线册那一刻却请不出在场线的单子")
    @PostMapping("/dangling")
    @RequiresPermissions("salt:saltIodLine:list")
    @ResponseBody
    public AjaxResult dangling(@RequestBody List<ITSaltIodLineService.DanglingRef> entries) {
        List<ITSaltIodLineService.DanglingRef> dangling = saltIodLineService.findDangling(entries);
        AjaxResult result = AjaxResult.success();
        result.put("data", dangling);
        result.put("total", dangling.size());
        return result;
    }

    @Log(title = "碘含量判定线立线", action = "add")
    @ApiOperation(value = "立线（新增）", notes = "三分界数缺一不可且须严格递增，回执指名哪一栏")
    @PostMapping("/add")
    @RequiresPermissions("salt:saltIodLine:add")
    @ResponseBody
    public AjaxResult add(TSaltIodLine record) {
        try {
            return toAjax(saltIodLineService.insertTSaltIodLine(record));
        } catch (IllegalArgumentException e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    @Log(title = "碘含量判定线改线", action = "edit")
    @ApiOperation(value = "改线（保存）", notes = "校验口径同立线")
    @PostMapping("/edit")
    @RequiresPermissions("salt:saltIodLine:edit")
    @ResponseBody
    public AjaxResult editSave(TSaltIodLine record) {
        try {
            return toAjax(saltIodLineService.updateTSaltIodLine(record));
        } catch (IllegalArgumentException e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    @Log(title = "碘含量判定线停用", action = "deactivate")
    @ApiOperation(value = "停用", notes = "册里翻得着、屏上列得出，只是不再捞它出来比数")
    @PostMapping("/deactivate")
    @RequiresPermissions("salt:saltIodLine:edit")
    @ResponseBody
    public AjaxResult deactivate(Long id) {
        return toAjax(saltIodLineService.deactivate(id));
    }

    @Log(title = "碘含量判定线删除", action = "remove")
    @ApiOperation(value = "删除", notes = "删除标记置 1")
    @DeleteMapping("/remove")
    @RequiresPermissions("salt:saltIodLine:remove")
    @ResponseBody
    public AjaxResult remove(String ids) {
        return toAjax(saltIodLineService.deleteTSaltIodLineByIds(ids));
    }

    /** 问话时刻两种写法都收：整日（按 00:00:00 起算）与完整时分秒；不合法返回 null。 */
    private static Date parseAt(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String t = text.trim();
        String pattern = t.length() <= 10 ? "yyyy-MM-dd" : "yyyy-MM-dd HH:mm:ss";
        SimpleDateFormat sdf = new SimpleDateFormat(pattern);
        sdf.setLenient(false);
        try {
            return sdf.parse(t);
        } catch (ParseException e) {
            return null;
        }
    }
}
