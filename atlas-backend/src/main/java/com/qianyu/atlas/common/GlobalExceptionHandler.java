package com.qianyu.atlas.common;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BizException.class)
    public ApiResponse<Void> handleBizException(BizException exception) {
        return ApiResponse.fail(exception.getCode(), exception.getMessage());
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            BindException.class,
            ConstraintViolationException.class,
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ApiResponse<Void> handleBadRequest(Exception exception) {
        return ApiResponse.fail(400, badRequestMessage(exception));
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleException(Exception exception) {
        log.error("Unhandled backend exception", exception);
        return ApiResponse.fail(500, "服务器内部错误");
    }

    private String badRequestMessage(Exception exception) {
        if (exception instanceof MethodArgumentNotValidException validException) {
            return fieldErrorMessage(validException.getBindingResult().getFieldErrors(),
                    validException.getBindingResult().getAllErrors().stream()
                            .map(DefaultMessageSourceResolvable::getDefaultMessage)
                            .collect(Collectors.toList()));
        }
        if (exception instanceof BindException bindException) {
            return fieldErrorMessage(bindException.getBindingResult().getFieldErrors(),
                    bindException.getBindingResult().getAllErrors().stream()
                            .map(DefaultMessageSourceResolvable::getDefaultMessage)
                            .collect(Collectors.toList()));
        }
        if (exception instanceof ConstraintViolationException violationException) {
            String message = violationException.getConstraintViolations().stream()
                    .limit(5)
                    .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                    .collect(Collectors.joining("；"));
            return message.isBlank() ? "请求参数不合法" : message;
        }
        if (exception instanceof MissingServletRequestParameterException missingException) {
            return "缺少必要参数：" + missingException.getParameterName();
        }
        if (exception instanceof MethodArgumentTypeMismatchException mismatchException) {
            return "参数类型不正确：" + mismatchException.getName();
        }
        if (exception instanceof HttpMessageNotReadableException) {
            return "请求体格式不正确";
        }
        return "请求参数不合法";
    }

    private String fieldErrorMessage(java.util.List<FieldError> fieldErrors, java.util.List<String> objectErrors) {
        String message = fieldErrors.stream()
                .limit(5)
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("；"));
        if (!message.isBlank()) return message;
        message = objectErrors.stream()
                .filter(value -> value != null && !value.isBlank())
                .limit(5)
                .collect(Collectors.joining("；"));
        return message.isBlank() ? "请求参数不合法" : message;
    }
}
