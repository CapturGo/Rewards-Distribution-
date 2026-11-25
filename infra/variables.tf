variable "project_id" {
  description = "GCP project ID"
  type        = string
  default     = "capturgo-478506"
}

variable "region" {
  description = "GCP region"
  type        = string
  default     = "asia-east2"
}

variable "service_name" {
  description = "Cloud Run service name"
  type        = string
  default     = "captur-payments"
}

variable "github_owner" {
  description = "GitHub org/user that owns the repo"
  type        = string
  default     = "CapturGo"
}

variable "github_repo" {
  description = "GitHub repository name"
  type        = string
  default     = "Rewards-Distribution-"
}

variable "github_branch" {
  description = "Branch that triggers builds"
  type        = string
  default     = "main"
}
