package com.capturgo.payments.core.exception

import org.springframework.http.HttpStatus

open class ApiException(
  val errorCode: String,
  message: String,
  val httpStatus: HttpStatus = HttpStatus.BAD_REQUEST,
) : RuntimeException(message)

class UnauthorizedException(message: String = "Authentication required") :
  ApiException("UNAUTHORIZED", message, HttpStatus.UNAUTHORIZED)

class ForbiddenException(message: String = "Insufficient permissions") :
  ApiException("FORBIDDEN", message, HttpStatus.FORBIDDEN)

class NotFoundException(message: String = "Resource not found") :
  ApiException("NOT_FOUND", message, HttpStatus.NOT_FOUND)

class ConflictException(message: String) :
  ApiException("CONFLICT", message, HttpStatus.CONFLICT)

class UnprocessableException(message: String) :
  ApiException("UNPROCESSABLE", message, HttpStatus.UNPROCESSABLE_ENTITY)

class UpstreamException(message: String, cause: Throwable? = null) :
  ApiException("UPSTREAM_ERROR", message, HttpStatus.BAD_GATEWAY)

class FeatureDisabledException(message: String) :
  ApiException("FEATURE_DISABLED", message, HttpStatus.SERVICE_UNAVAILABLE)

data class ErrorResponse(
  val errorCode: String,
  val numericCode: Int,
  val error: String,
  val message: String,
  val timestamp: String,
  val path: String,
  val requestId: String,
)
