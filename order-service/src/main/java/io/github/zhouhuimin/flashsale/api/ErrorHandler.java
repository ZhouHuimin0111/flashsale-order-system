package io.github.zhouhuimin.flashsale.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.util.Map;

@RestControllerAdvice
public class ErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);
    @ExceptionHandler(ApiError.class) ResponseEntity<?> business(ApiError e) { return ResponseEntity.status(e.status()).body(Map.of("code", e.code(), "message", e.getMessage())); }
    @ExceptionHandler({MethodArgumentNotValidException.class, MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    ResponseEntity<?> invalid(Exception e) { return ResponseEntity.badRequest().body(Map.of("code", "INVALID_INPUT", "message", "请求参数无效，请检查字段格式")); }
    @ExceptionHandler(Exception.class) ResponseEntity<?> unavailable(Exception e) {
        log.error("Request failed", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("code", "SERVICE_UNAVAILABLE", "message", "服务暂时不可用；秒杀提交结果不确定时请先查询我的订单"));
    }
}
