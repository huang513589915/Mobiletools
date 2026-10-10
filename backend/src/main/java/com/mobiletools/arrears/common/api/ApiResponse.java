package com.mobiletools.arrears.common.api;

public record ApiResponse<T>(String code, String message, T data) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("OK", "success", data);
    }
}
