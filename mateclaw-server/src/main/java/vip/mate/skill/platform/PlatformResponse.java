package vip.mate.skill.platform;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 平台端响应包装类（仅用于反序列化平台端 API 返回值）
 * <p>
 * 兼容平台端的响应格式差异：
 * - code 可能是 String "200" 也可能是 int 200，统一用 String 接收
 * - message 字段名可能是 "message" 或 "msg"
 * <p>
 * 注意：此类仅供 {@link PlatformSkillClient} 解析远程响应使用，
 * 不要与客户端自身的 {@link vip.mate.common.result.R} 混淆。
 *
 * @author MateClaw Team
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlatformResponse<T> {

    /**
     * 状态码（String 兼容平台端，可能返回 "200" 或 200）
     */
    @JsonAlias({"code"})
    private String code;

    /**
     * 提示信息（兼容 "message" 和 "msg" 两种字段名）
     */
    @JsonAlias({"message", "msg"})
    private String message;

    /**
     * 响应数据
     */
    private T data;

    /**
     * 判断响应是否成功
     */
    public boolean isSuccess() {
        return "200".equals(code);
    }
}
