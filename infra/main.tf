# Artifact Registry repo for service images (matches cloudbuild.yaml push target)
resource "google_artifact_registry_repository" "service" {
  project       = var.project_id
  location      = var.region
  repository_id = "captur-release"
  format        = "DOCKER"
  description   = "CapturGO release images"
}

# Cloud Build trigger: builds cloudbuild.yaml on push to main.
resource "google_cloudbuild_trigger" "service" {
  project  = var.project_id
  location = var.region
  name     = "${var.service_name}-deploy"

  github {
    owner = var.github_owner
    name  = var.github_repo
    push {
      branch = "^${var.github_branch}$"
    }
  }

  filename           = "cloudbuild.yaml"
  service_account    = google_service_account.cloudbuild.id
  included_files     = ["**"]
  ignored_files      = ["docs/**", "**/*.md", ".git/**"]

  depends_on = [
    google_project_service.apis,
    google_artifact_registry_repository_iam_member.cloudbuild_writer,
  ]
}

# Service account Cloud Build runs as — needs to push to Artifact Registry
# and deploy revisions to Cloud Run.
resource "google_service_account" "cloudbuild" {
  project      = var.project_id
  account_id   = "${var.service_name}-build"
  display_name = "${var.service_name} Cloud Build"
}

resource "google_project_service" "apis" {
  for_each = toset([
    "cloudbuild.googleapis.com",
    "run.googleapis.com",
    "artifactregistry.googleapis.com",
  ])
  project            = var.project_id
  service            = each.value
  disable_on_destroy = false
}

resource "google_artifact_registry_repository_iam_member" "cloudbuild_writer" {
  project    = var.project_id
  location   = var.region
  repository = google_artifact_registry_repository.service.name
  role       = "roles/artifactregistry.writer"
  member     = "serviceAccount:${google_service_account.cloudbuild.email}"
}

# Deploy permission: the build SA runs `gcloud run deploy` at the end of
# cloudbuild.yaml. Project-level run.developer is used because the Cloud Run
# service itself is NOT managed by Terraform (it already exists with env vars
# and secrets that Terraform must not touch).
resource "google_project_iam_member" "cloudbuild_run_developer" {
  project = var.project_id
  role    = "roles/run.developer"
  member  = "serviceAccount:${google_service_account.cloudbuild.email}"
}
