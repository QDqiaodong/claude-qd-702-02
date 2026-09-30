package com.metrology.lab.common;

import java.util.List;
import java.util.Map;

/**
 * 统一返回包装：成功 {ok:true, data:...}，失败 {ok:false, message:"..."}。
 * 标准器占用冲突（409）额外携带 conflicts / busyOrderNos，供前端逐件标红并保留用户原选择。
 */
public class ApiResponse<T> {

    private boolean ok;
    private String message;
    private T data;
    /** 占用冲突逐条原因 */
    private List<String> conflicts;
    /** 发生冲突的标准器 id → 占用方委托单号 */
    private Map<Long, String> busyOrderNos;

    public ApiResponse() {
    }

    public ApiResponse(boolean ok, String message, T data) {
        this.ok = ok;
        this.message = message;
        this.data = data;
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, "OK", data);
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(true, message, data);
    }

    public static <T> ApiResponse<T> fail(String message) {
        return new ApiResponse<>(false, message, null);
    }

    public static ApiResponse<Void> conflict(String message, List<String> conflicts,
                                             Map<Long, String> busyOrderNos) {        ApiResponse<Void> resp = new ApiResponse<>(false, message, null);
        resp.conflicts = conflicts;
        resp.busyOrderNos = busyOrderNos;
        return resp;
    }

    public boolean isOk() {
        return ok;
    }

    public void setOk(boolean ok) {
        this.ok = ok;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public List<String> getConflicts() {
        return conflicts;
    }

    public void setConflicts(List<String> conflicts) {
        this.conflicts = conflicts;
    }

    public Map<Long, String> getBusyOrderNos() {
        return busyOrderNos;
    }

    public void setBusyOrderNos(Map<Long, String> busyOrderNos) {
        this.busyOrderNos = busyOrderNos;
    }
}
