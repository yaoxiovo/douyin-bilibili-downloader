package com.yaoxiovo.downloader.model;

import com.google.gson.Gson;

/**
 * Standard Bridge Response Envelope.
 *
 * @param <T> Payload type
 */
public class BridgeResponse<T> {
    private boolean ok;
    private int code;
    private String msg;
    private T data;

    public BridgeResponse() {}

    public BridgeResponse(boolean ok, int code, String msg, T data) {
        this.ok = ok;
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public static <T> BridgeResponse<T> success(T data) {
        return new BridgeResponse<>(true, 200, "success", data);
    }

    public static <T> BridgeResponse<T> error(int code, String msg) {
        return new BridgeResponse<>(false, code, msg, null);
    }

    public boolean isOk() {
        return ok;
    }

    public void setOk(boolean ok) {
        this.ok = ok;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public String toJson() {
        return new Gson().toJson(this);
    }
}
