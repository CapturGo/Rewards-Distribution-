package com.capturgo.payments.core.exception

import jakarta.servlet.http.HttpServletRequest
import java.time.Instant
import java.util.UUID
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.resource.NoResourceFoundException

@RestControllerAdvice
class ErrorHandler {

  @ExceptionHandler(ApiException::class)
  fun handleApi(e: ApiException, req: HttpServletRequest): ResponseEntity<ErrorResponse> {
    val body = ErrorResponse(
      errorCode = e.errorCode,
      numericCode = e.httpStatus.value(),
      error = e.httpStatus.reasonPhrase,
      message = e.message ?: e.errorCode,
      timestamp = Instant.now().toString(),
      path = req.requestURI,
      requestId = req.getAttribute(REQUEST_ID_ATTR) as? String ?: UUID.randomUUID().toString(),
    )
    return ResponseEntity.status(e.httpStatus).body(body)
  }

  @ExceptionHandler(NoResourceFoundException::class)
  fun handleNotFound(req: HttpServletRequest): ResponseEntity<ErrorResponse> {
    val body = ErrorResponse(
      errorCode = "NOT_FOUND",
      numericCode = 404,
      error = "Not Found",
      message = "Endpoint not found",
      timestamp = Instant.now().toString(),
      path = req.requestURI,
      requestId = UUID.randomUUID().toString(),
    )
    return ResponseEntity.status(404).body(body)
  }

  @ExceptionHandler(Exception::class)
  fun handleGeneric(e: Exception, req: HttpServletRequest): ResponseEntity<ErrorResponse> {
    val body = ErrorResponse(
      errorCode = "INTERNAL_ERROR",
      numericCode = 500,
      error = "Internal Server Error",
      message = "An unexpected error occurred",
      timestamp = Instant.now().toString(),
      path = req.requestURI,
      requestId = UUID.randomUUID().toString(),
    )
    return ResponseEntity.status(500).body(body)
  }

  companion object {
    const val REQUEST_ID_ATTR = "requestId"
  }
}
