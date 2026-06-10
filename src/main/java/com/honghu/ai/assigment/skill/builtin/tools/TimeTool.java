package com.honghu.ai.assigment.skill.builtin.tools;

import com.honghu.ai.assigment.entity.User;
import com.honghu.ai.assigment.skill.builtin.annotation.NativeSkill;
import com.honghu.ai.assigment.skill.builtin.annotation.NativeTool;
import com.honghu.ai.assigment.skill.builtin.annotation.ToolParam;
import com.honghu.ai.assigment.skill.core.DangerLevel;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 内置日期时间技能。
 *
 * <p>这个技能是必装能力，因为它低风险、确定性强，并且很多对话都会涉及“今天、明天、当前时间”等相对时间。
 * 类上的 {@code @NativeSkill} 是元数据来源：应用启动注册器会用这些值 upsert {@code skill} 表，
 * 所以数据库 seed 不应该被当成 mandatory/defaultEnabled 的最终权威。</p>
 */
@NativeSkill(
        key = "time",
        displayName = "时间日期",
        description = "查询当前时间、日期、时区和相对日期",
        icon = "TIME",
        category = "通用",
        defaultEnabled = true,
        mandatory = true,
        requiredRole = User.UserRole.USER)
public class TimeTool {

    /**
     * 返回指定时区下的当前服务器时间。
     *
     * @param timezone IANA 时区，例如 {@code Asia/Shanghai}；为空时使用服务器默认时区
     * @return 稳定的结构化时间字段，便于模型引用或继续计算
     */
    @NativeTool(
            name = "now",
            description = "返回服务器当前日期时间，可指定 IANA 时区，例如 Asia/Shanghai、UTC 或 America/New_York。",
            dangerLevel = DangerLevel.SAFE)
    public Map<String, Object> now(
            @ToolParam(description = "IANA 时区，留空使用服务器默认时区。", required = false) String timezone) {
        // 有传时区就按传入时区解析；否则使用 JVM 默认时区。
        ZoneId zone = timezone == null || timezone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(timezone.trim());
        // 在目标时区获取当前时间。
        ZonedDateTime now = ZonedDateTime.now(zone);
        // 单独取星期，后面同时返回英文枚举和中文名称。
        DayOfWeek dayOfWeek = now.getDayOfWeek();

        // 使用 LinkedHashMap 保持返回字段顺序稳定。
        Map<String, Object> result = new LinkedHashMap<>();
        // iso 带偏移量，适合精确引用。
        result.put("iso", now.toOffsetDateTime().toString());
        // localDate 只含日期。
        result.put("localDate", now.toLocalDate().toString());
        // localTime 去掉纳秒，避免无意义精度干扰模型回答。
        result.put("localTime", now.toLocalTime().withNano(0).toString());
        // 返回最终采用的时区 id。
        result.put("zone", zone.getId());
        // epochMillis 方便模型或前端做进一步时间差计算。
        result.put("epochMillis", now.toInstant().toEpochMilli());
        // 英文星期枚举，例如 MONDAY。
        result.put("dayOfWeek", dayOfWeek.name());
        // 中文星期名称，例如 星期一。
        result.put("dayOfWeekTextZh", dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINA));
        return result;
    }

    /**
     * 按“今天 + 天数偏移”计算相对日期。
     *
     * @param daysOffset 相对今天的天数；0=今天，1=明天，-1=昨天
     * @param timezone 用于确定“今天”的 IANA 时区；为空时使用服务器默认时区
     * @return 结构化相对日期结果
     */
    @NativeTool(
            name = "relative_date",
            description = "按天数偏移计算相对日期，例如 0=今天、1=明天、-1=昨天。",
            dangerLevel = DangerLevel.SAFE)
    public Map<String, Object> relativeDate(
            @ToolParam(description = "相对今天的天数偏移，例如 0、1、-1、7。") Double daysOffset,
            @ToolParam(description = "IANA 时区，留空使用服务器默认时区。", required = false) String timezone) {
        // 与 now 一样，先确定计算“今天”所用的时区。
        ZoneId zone = timezone == null || timezone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(timezone.trim());
        // 模型传 Double 是因为工具参数统一按 number；这里转成天数整数。
        int offset = daysOffset == null ? 0 : daysOffset.intValue();
        // 在目标时区取今天，然后加上偏移天数。
        LocalDate target = LocalDate.now(zone).plusDays(offset);

        // 返回结构化字段，避免模型自行推断星期导致错误。
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", target.toString());
        result.put("zone", zone.getId());
        result.put("daysOffset", offset);
        result.put("dayOfWeek", target.getDayOfWeek().name());
        result.put("dayOfWeekTextZh", target.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA));
        return result;
    }
}
