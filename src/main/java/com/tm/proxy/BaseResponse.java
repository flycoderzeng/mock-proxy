package com.tm.proxy;

import com.alibaba.fastjson.JSON;
import lombok.Data;

@Data
public class BaseResponse {
    int code = 0;
    String message = "success";
    Object data = null;

    public BaseResponse(String message) {
        this.message = message;
    }

    public BaseResponse(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public BaseResponse(Object data) {
        this.data = data;
    }

    public static String baseSuccess(String message) {
        return JSON.toJSONString(new BaseResponse(message));
    }

    public static String baseFail(String message) {
        return JSON.toJSONString(new BaseResponse(1, message));
    }

    public static String baseSuccess(Object data) {
        return JSON.toJSONString(new BaseResponse(data));
    }
}
