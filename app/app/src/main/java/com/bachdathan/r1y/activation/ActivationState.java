package com.bachdathan.r1y.activation;

/** Trạng thái kích hoạt Xiaozhi dùng chung để giao diện web (loa không có màn hình) hiển thị mã. */
public final class ActivationState {
    public static volatile String code = "";
    public static volatile String error = "";
    public static volatile boolean activated = false;
    public static volatile int attempt = 0;
    public static volatile int max = 0;

    private ActivationState() {}
}
