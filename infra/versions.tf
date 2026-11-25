terraform {
  required_version = ">= 1.5"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.0"
    }
  }

  # Uncomment and configure for remote state before first apply.
  # backend "gcs" {
  #   bucket = "capturgo-478506-terraform-state"
  #   prefix = "captur-payments"
  # }
}

provider "google" {
  project = var.project_id
  region  = var.region
}
