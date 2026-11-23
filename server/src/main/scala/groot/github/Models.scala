package groot.github

import framework.model.JsonModel
import framework.validation.{Contracts, Validator, contract}
import io.circe.{Codec, Json}

final case class GitHubConfig(
    @contract[String](Contracts.isHttpUrl, "must be an absolute http(s) URL") apiBase: String = "https://api.github.com",
    @contract[String](Contracts.isHttpUrl, "must be an absolute http(s) URL") oauthBase: String = "https://github.com",
    @contract[String](Contracts.nonBlank) oauthClientId: String = sys.env.get("GITHUB_CLIENT_ID").filter(_.nonEmpty).getOrElse(GitHubAuth.defaultOAuthClientId),
    @contract[String](Contracts.nonBlank) defaultScopes: String = GitHubAuth.defaultScopes,
    @contract[String](Contracts.isDuration) timeout: String = "30s",
    @contract[Int](_ > 0) maxJsonBytes: Int = GitHubClient.maxJsonBytes,
    @contract[Int](_ > 0) maxRawBytes: Int = GitHubClient.maxRawBytes
) derives JsonModel, Validator

final case class GitHubFailure(code: String, message: String) extends RuntimeException(message)

final case class StoredGitHubAuth(token: String, login: String, name: Option[String] = None,
    scopes: Vector[String] = Vector.empty, updatedAt: String) derives Codec.AsObject, JsonModel
final case class LogoutResult(loggedOut: Boolean, login: Option[String] = None) derives JsonModel
final case class RateLimitInfo(limit: Int, remaining: Int, used: Int, resetAt: String) derives JsonModel
final case class WhoamiResult(login: String, id: Long, name: Option[String] = None, email: Option[String] = None,
    scopes: Vector[String] = Vector.empty, rateLimit: Option[RateLimitInfo] = None) derives JsonModel

final case class ApiRequest(path: String, method: String = "GET", query: Map[String, String] = Map.empty,
    headers: Map[String, String] = Map.empty, body: Option[Json] = None, paginate: Boolean = false,
    maxPages: Int = 10, repo: Option[String] = None) derives JsonModel
final case class ApiResponse(status: Int, headers: Map[String, String], body: Json) derives JsonModel
final case class GraphqlRequest(query: String, variables: Map[String, Json] = Map.empty,
    operationName: Option[String] = None) derives JsonModel

final case class NotificationSummary(id: String, unread: Boolean, reason: String, updatedAt: String,
    repo: String, subjectType: String, subjectTitle: String, subjectNumber: Option[Int] = None) derives JsonModel

enum SearchKind derives JsonModel:
  case repos, code, issues, prs, commits

final case class SearchItem(kind: String, repo: Option[String] = None, number: Option[Int] = None, title: String,
    state: Option[String] = None, author: Option[String] = None, path: Option[String] = None,
    sha: Option[String] = None, stars: Option[Int] = None, updatedAt: Option[String] = None, url: String) derives JsonModel
final case class SearchResult(totalCount: Int, incompleteResults: Boolean, items: Vector[SearchItem]) derives JsonModel

final case class RepoDetail(fullName: String, description: Option[String] = None, isPrivate: Boolean,
    isFork: Boolean, isArchived: Boolean, defaultBranch: String, language: Option[String] = None,
    topics: Vector[String] = Vector.empty, stars: Int, forks: Int, openIssues: Int,
    pushedAt: Option[String] = None, updatedAt: Option[String] = None, url: String,
    parentFullName: Option[String] = None) derives JsonModel
final case class RepoContentEntry(name: String, path: String, kind: String, size: Long, sha: String) derives JsonModel
final case class RepoContent(repo: String, path: String, kind: String, size: Long = 0L, sha: Option[String] = None,
    content: Option[String] = None, base64: Option[String] = None,
    entries: Vector[RepoContentEntry] = Vector.empty, url: Option[String] = None) derives JsonModel

final case class CheckItem(name: String, status: String, conclusion: Option[String] = None,
    detailsUrl: Option[String] = None) derives JsonModel
final case class PrChecksSummary(overallState: Option[String] = None, total: Int = 0, passed: Int = 0,
    failed: Int = 0, pending: Int = 0, items: Vector[CheckItem] = Vector.empty) derives JsonModel
final case class PrSummary(number: Int, title: String, state: String, isDraft: Boolean,
    author: Option[String] = None, headRef: String, baseRef: String, labels: Vector[String] = Vector.empty,
    reviewDecision: Option[String] = None, checksState: Option[String] = None, updatedAt: String, url: String) derives JsonModel
final case class PrDetail(number: Int, title: String, state: String, isDraft: Boolean,
    author: Option[String] = None, headRef: String, headSha: String, baseRef: String,
    mergeable: Option[String] = None, mergeStateStatus: Option[String] = None, reviewDecision: Option[String] = None,
    additions: Int = 0, deletions: Int = 0, changedFiles: Int = 0, commentsCount: Int = 0, unresolvedThreads: Int = 0,
    labels: Vector[String] = Vector.empty, createdAt: String, updatedAt: String, mergedAt: Option[String] = None,
    mergedBy: Option[String] = None, url: String, body: String = "", checks: PrChecksSummary = PrChecksSummary()) derives JsonModel
final case class PrChangedFile(filename: String, status: String, additions: Int, deletions: Int,
    previousFilename: Option[String] = None) derives JsonModel
final case class PrDiffResult(number: Int, files: Vector[PrChangedFile] = Vector.empty,
    diff: Option[String] = None, truncated: Boolean = false) derives JsonModel

final case class IssueComment(id: Long, author: Option[String] = None, body: String, createdAt: String,
    updatedAt: Option[String] = None, url: String) derives JsonModel
final case class ReviewCommentItem(author: Option[String] = None, body: String, createdAt: String, url: String) derives JsonModel
final case class PrReviewThread(id: String, path: String, line: Option[Int] = None, isResolved: Boolean,
    isOutdated: Boolean, comments: Vector[ReviewCommentItem] = Vector.empty) derives JsonModel
final case class PrCommentsResult(number: Int, comments: Vector[IssueComment] = Vector.empty,
    reviewThreads: Vector[PrReviewThread] = Vector.empty) derives JsonModel

final case class PrCreateRequest(title: String, head: String, base: Option[String] = None, body: Option[String] = None,
    draft: Boolean = false, maintainerCanModify: Boolean = true, labels: Vector[String] = Vector.empty,
    repo: Option[String] = None) derives JsonModel
final case class PrUpdateRequest(number: Int, title: Option[String] = None, body: Option[String] = None,
    state: Option[String] = None, base: Option[String] = None, draft: Option[Boolean] = None,
    addLabels: Vector[String] = Vector.empty, removeLabels: Vector[String] = Vector.empty,
    repo: Option[String] = None) derives JsonModel
final case class PrReviewRequest(number: Int, event: String = "COMMENT", body: Option[String] = None,
    repo: Option[String] = None) derives JsonModel
final case class PrReviewReceipt(id: Long, state: String, author: Option[String] = None,
    submittedAt: Option[String] = None, url: String) derives JsonModel
final case class PrMergeResult(merged: Boolean, sha: Option[String] = None, message: String,
    branchDeleted: Boolean = false) derives JsonModel

final case class IssueItem(number: Int, title: String, state: String, stateReason: Option[String] = None,
    author: Option[String] = None, labels: Vector[String] = Vector.empty, assignees: Vector[String] = Vector.empty,
    commentsCount: Int = 0, createdAt: String, updatedAt: String, closedAt: Option[String] = None, url: String,
    body: Option[String] = None, comments: Vector[IssueComment] = Vector.empty) derives JsonModel
final case class IssueCreateRequest(title: String, body: Option[String] = None, labels: Vector[String] = Vector.empty,
    assignees: Vector[String] = Vector.empty, repo: Option[String] = None) derives JsonModel
final case class IssueUpdateRequest(number: Int, title: Option[String] = None, body: Option[String] = None,
    state: Option[String] = None, stateReason: Option[String] = None, labels: Option[Vector[String]] = None,
    assignees: Option[Vector[String]] = None, repo: Option[String] = None) derives JsonModel
final case class IssueCommentRequest(number: Int, body: String, repo: Option[String] = None) derives JsonModel

final case class WorkflowStep(number: Int, name: String, status: String, conclusion: Option[String] = None) derives JsonModel
final case class WorkflowJob(id: Long, name: String, status: String, conclusion: Option[String] = None,
    startedAt: Option[String] = None, completedAt: Option[String] = None, url: String,
    failedSteps: Vector[String] = Vector.empty, steps: Vector[WorkflowStep] = Vector.empty) derives JsonModel
final case class WorkflowRun(id: Long, name: String, workflowId: Long, headBranch: Option[String] = None,
    headSha: String, event: String, status: String, conclusion: Option[String] = None, runNumber: Int,
    runAttempt: Int = 1, actor: Option[String] = None, createdAt: String, updatedAt: String, url: String,
    jobs: Vector[WorkflowJob] = Vector.empty) derives JsonModel
final case class WorkflowDispatchRequest(workflow: String, ref: String, inputs: Map[String, String] = Map.empty,
    repo: Option[String] = None) derives JsonModel
final case class ActionAck(ok: Boolean, message: String) derives JsonModel

final case class ReleaseAsset(name: String, size: Long, downloadCount: Int, contentType: String, downloadUrl: String) derives JsonModel
final case class ReleaseInfo(id: Long, tagName: String, name: Option[String] = None,
    targetCommitish: Option[String] = None, isDraft: Boolean, isPrerelease: Boolean, author: Option[String] = None,
    createdAt: String, publishedAt: Option[String] = None, url: String, body: Option[String] = None,
    assets: Vector[ReleaseAsset] = Vector.empty) derives JsonModel
final case class ReleaseCreateRequest(tagName: String, name: Option[String] = None, body: Option[String] = None,
    targetCommitish: Option[String] = None, draft: Boolean = false, prerelease: Boolean = false,
    generateReleaseNotes: Boolean = false, repo: Option[String] = None) derives JsonModel
