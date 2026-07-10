package vip.mate.agent.platform;

/**
 * 平台服务调用异常
 * <p>
 * 用于区分平台服务不可用和业务错误，让调用方能够根据异常类型进行不同的处理。
 *
 * @author MateClaw Team
 */
public class PlatformServiceException extends RuntimeException {

    private final ErrorType errorType;

    public enum ErrorType {
        /** 平台服务不可达（网络错误、超时等） */
        UNREACHABLE,
        /** 认证失败（令牌无效、过期等） */
        AUTH_ERROR,
        /** 平台返回业务错误 */
        BUSINESS_ERROR,
        /** 响应解析失败 */
        PARSE_ERROR
    }

    public PlatformServiceException(ErrorType errorType, String message) {
        super(message);
        this.errorType = errorType;
    }

    public PlatformServiceException(ErrorType errorType, String message, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
    }

    public ErrorType getErrorType() {
        return errorType;
    }

    public boolean isUnreachable() {
        return errorType == ErrorType.UNREACHABLE;
    }

    public boolean isAuthError() {
        return errorType == ErrorType.AUTH_ERROR;
    }
}
