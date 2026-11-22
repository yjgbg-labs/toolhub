package groot.github

import framework.http.ErrorBody
import framework.model.JsonModel
import framework.rpc.CommandsScope
import framework.rpcDsl.*
import io.circe.Json

object GitHubRoutes:
  private given ParamCodec[SearchKind] = ParamCodec.scalar { value =>
    JsonModel[SearchKind].decoder.decodeJson(Json.fromString(value)).left.map(_.message)
  }

  def register(service: GitHubService)(using CommandsScope): Unit = commands("github") {
    description("Access GitHub repositories, pull requests, issues, Actions runs, releases, notifications, search and raw REST/GraphQL APIs")
    exposeOn(cli, codemode, graaljs)

    val invalid = response.json[ErrorBody](400) { description("Invalid GitHub parameters or uninferrable repository") }
    val unauth = response.json[ErrorBody](401) { description("Not logged in to GitHub or invalid token") }
    val forbidden = response.json[ErrorBody](403) { description("Insufficient token scopes or access denied") }
    val missing = response.json[ErrorBody](404) { description("GitHub resource not found") }
    val conflict = response.json[ErrorBody](409) { description("GitHub state conflict") }
    val large = response.json[ErrorBody](413) { description("GitHub response exceeds size limit") }
    val unprocessable = response.json[ErrorBody](422) { description("GitHub validation failed") }
    val rateLimited = response.json[ErrorBody](429) { description("GitHub API rate limit exceeded") }
    val upstream = response.json[ErrorBody](502) { description("GitHub API or GraphQL call failed") }
    val timedOut = response.json[ErrorBody](504) { description("GitHub API call or device authorization timed out") }

    recover { case e: GitHubFailure =>
      val b = ErrorBody(e.code, e.getMessage)
      e.code match
        case "invalid_github" => invalid(b)
        case "github_unauthenticated" => unauth(b)
        case "github_forbidden" => forbidden(b)
        case "github_not_found" => missing(b)
        case "github_conflict" => conflict(b)
        case "github_limit" => large(b)
        case "github_validation" => unprocessable(b)
        case "github_rate_limited" => rateLimited(b)
        case "github_timeout" => timedOut(b)
        case _ => upstream(b)
    }

    command[SSE[PlainText]]("login") {
      exposeOn(cli)
      description("Log in to GitHub via interactive browser OAuth Device Flow (default) or a Personal Access Token (--token)")
      val token = param[Option[String]]("token", "Optional Personal Access Token (or '-' for stdin); omit for browser Device Flow")
      val scopes = param[String]("scopes", "Comma-separated OAuth scopes for Device Flow") { default(GitHubAuth.defaultScopes) }
      val clientId = param[Option[String]]("clientId", "Optional OAuth App Client ID override")
      handle { service.login(token.value, scopes.value, clientId.value) }
    }
    command[LogoutResult]("logout") {
      exposeOn(cli)
      description("Clear the stored GitHub token from Groot Server")
      handle { service.logout }
    }
    command[WhoamiResult]("whoami") {
      description("Inspect the authenticated GitHub user, active token scopes and current REST rate limit")
      handle { service.whoami }
    }
    command[ApiResponse]("api") {
      description("Send an authenticated GitHub REST API request; supports {owner}/{repo} placeholders and Link pagination")
      val cwd = param[Option[String]]("cwd", "Caller working directory for {owner}/{repo} inference")
      val input = body[ApiRequest]("REST method, path, query, headers, JSON body, paginate flag and optional repo override")
      handle { service.api(cwd.value, input.value) }
    }
    command[Json]("graphql") {
      description("Execute an authenticated GitHub GraphQL query or mutation and return its data object")
      val input = body[GraphqlRequest]("GraphQL query string, variables map and optional operationName")
      handle { service.graphql(input.value) }
    }
    command[Vector[NotificationSummary]]("notifications") {
      description("List GitHub notifications (mentions, review requests, CI alerts) and optionally mark them read")
      val all = param[Boolean]("all", "Include already-read notifications") { default(false) }
      val participating = param[Boolean]("participating", "Only notifications where directly participating or mentioned") { default(false) }
      val repo = param[Option[String]]("repo", "Optional owner/repo filter")
      val since = param[Option[String]]("since", "Only notifications updated after this ISO-8601 timestamp")
      val limit = param[Int]("limit", "Maximum notifications to return") { default(30); minimum(1); maximum(100) }
      val markRead = param[Boolean]("markRead", "Mark matching notifications as read after fetching") { default(false) }
      handle { service.notifications(all.value, participating.value, repo.value, since.value, limit.value, markRead.value) }
    }
    command[SearchResult]("search") {
      description("Search GitHub repositories, code, issues, pull requests or commits")
      val kind = param[SearchKind]("kind", "Search domain: repos, code, issues, prs or commits")
      val query = param[String]("query", "GitHub search query syntax") { minLength(1) }
      val sort = param[Option[String]]("sort", "Optional sort field (e.g. updated, stars, committer-date)")
      val order = param[Option[String]]("order", "Sort direction: desc or asc")
      val limit = param[Int]("limit", "Maximum results to return") { default(20); minimum(1); maximum(100) }
      handle { service.search(kind.value, query.value, sort.value, order.value, limit.value) }
    }

    commands("repo") {
      description("Inspect GitHub repository metadata and read remote files or directories without cloning")
      val cwd = param[Option[String]]("cwd", "Caller working directory for automatic repository inference")
      val repo = param[Option[String]]("repo", "Target owner/repo; inferred from cwd git remote when omitted")
      command[RepoDetail]("get") {
        description("Read repository metadata, default branch, visibility, counters and timestamps")
        handle { service.repoGet(repo.value, cwd.value) }
      }
      command[RepoContent]("read") {
        description("Read a UTF-8/binary file or list directory entries from a repository at an optional git ref")
        val path = param[String]("path", "Repository-relative file or directory path (empty for root)") { default("") }
        val ref = param[Option[String]]("ref", "Branch, tag or commit SHA")
        handle { service.repoRead(path.value, ref.value, repo.value, cwd.value) }
      }
    }

    commands("pr") {
      description("List, inspect, diff, create, update, review and merge Pull Requests with aggregated CI check status")
      val cwd = param[Option[String]]("cwd", "Caller working directory for automatic repository inference")
      command[Vector[PrSummary]]("list") {
        description("List Pull Requests with draft flag, review decision and latest CI rollup state")
        val state = param[String]("state", "Filter by state: open, closed, merged or all") { default("open") }
        val head = param[Option[String]]("head", "Filter by head branch name")
        val base = param[Option[String]]("base", "Filter by base branch name")
        val author = param[Option[String]]("author", "Filter by author login")
        val label = param[Option[String]]("label", "Filter by label name")
        val limit = param[Int]("limit", "Maximum Pull Requests to return") { default(30); minimum(1); maximum(100) }
        val repo = param[Option[String]]("repo", "Target owner/repo; inferred from cwd when omitted")
        handle { service.prList(state.value, head.value, base.value, author.value, label.value, limit.value, repo.value, cwd.value) }
      }
      command[PrDetail]("get") {
        description("Read full Pull Request details including mergeability, review decision, unresolved threads and CI checks summary")
        val number = param[Int]("number", "Pull Request number") { minimum(1) }
        val repo = param[Option[String]]("repo", "Target owner/repo; inferred from cwd when omitted")
        handle { service.prGet(number.value, repo.value, cwd.value) }
      }
      command[PrDiffResult]("diff") {
        description("Read changed files and unified diff for a Pull Request")
        val number = param[Int]("number", "Pull Request number") { minimum(1) }
        val filesOnly = param[Boolean]("filesOnly", "Return only changed file summaries without unified diff text") { default(false) }
        val maxBytes = param[Int]("maxBytes", "Maximum UTF-8 bytes of unified diff") { default(262144); minimum(1024); maximum(1048576) }
        val repo = param[Option[String]]("repo", "Target owner/repo; inferred from cwd when omitted")
        handle { service.prDiff(number.value, filesOnly.value, maxBytes.value, repo.value, cwd.value) }
      }
      command[PrCommentsResult]("comments") {
        description("Read Pull Request conversation comments and inline review threads")
        val number = param[Int]("number", "Pull Request number") { minimum(1) }
        val unresolvedOnly = param[Boolean]("unresolvedOnly", "Only include unresolved inline review threads") { default(false) }
        val limit = param[Int]("limit", "Maximum comments and review threads to fetch") { default(50); minimum(1); maximum(100) }
        val repo = param[Option[String]]("repo", "Target owner/repo; inferred from cwd when omitted")
        handle { service.prComments(number.value, unresolvedOnly.value, limit.value, repo.value, cwd.value) }
      }
      command[PrSummary]("create") {
        description("Create a Pull Request (defaults base to the repository default branch)")
        val input = body[PrCreateRequest]("PR title, head branch, optional base branch, markdown body, draft flag and labels")
        handle { service.prCreate(cwd.value, input.value) }
      }
      command[PrDetail]("update") {
        description("Update a Pull Request's title, body, open/closed state, base branch, draft status or labels")
        val input = body[PrUpdateRequest]("PR number and fields to modify")
        handle { service.prUpdate(cwd.value, input.value) }
      }
      command[PrReviewReceipt]("review") {
        description("Submit a Pull Request review: COMMENT, APPROVE or REQUEST_CHANGES")
        val input = body[PrReviewRequest]("PR number, review event and markdown body")
        handle { service.prReview(cwd.value, input.value) }
      }
      command[PrMergeResult]("merge") {
        description("Merge a Pull Request via squash, merge or rebase and optionally delete its head branch")
        val number = param[Int]("number", "Pull Request number") { minimum(1) }
        val method = param[String]("method", "Merge strategy: squash, merge or rebase") { default("squash") }
        val commitTitle = param[Option[String]]("commitTitle", "Optional custom merge commit title")
        val commitMessage = param[Option[String]]("commitMessage", "Optional custom merge commit body")
        val sha = param[Option[String]]("sha", "Optional expected head commit SHA guard")
        val deleteBranch = param[Boolean]("deleteBranch", "Delete same-repository head branch after merging") { default(false) }
        val repo = param[Option[String]]("repo", "Target owner/repo; inferred from cwd when omitted")
        handle { service.prMerge(number.value, method.value, commitTitle.value, commitMessage.value, sha.value, deleteBranch.value, repo.value, cwd.value) }
      }
    }

    commands("issue") {
      description("List, inspect, create, update and comment on GitHub Issues")
      val cwd = param[Option[String]]("cwd", "Caller working directory for automatic repository inference")
      command[Vector[IssueItem]]("list") {
        description("List repository Issues (excluding Pull Requests)")
        val state = param[String]("state", "Filter by state: open, closed or all") { default("open") }
        val labels = param[Option[String]]("labels", "Comma-separated label names")
        val assignee = param[Option[String]]("assignee", "Filter by assignee login")
        val creator = param[Option[String]]("creator", "Filter by author login")
        val limit = param[Int]("limit", "Maximum Issues to return") { default(30); minimum(1); maximum(100) }
        val repo = param[Option[String]]("repo", "Target owner/repo; inferred from cwd when omitted")
        handle { service.issueList(state.value, labels.value, assignee.value, creator.value, limit.value, repo.value, cwd.value) }
      }
      command[IssueItem]("get") {
        description("Read Issue details and recent conversation comments")
        val number = param[Int]("number", "Issue number") { minimum(1) }
        val commentsLimit = param[Int]("commentsLimit", "Maximum comments to include") { default(50); minimum(0); maximum(100) }
        val repo = param[Option[String]]("repo", "Target owner/repo; inferred from cwd when omitted")
        handle { service.issueGet(number.value, commentsLimit.value, repo.value, cwd.value) }
      }
      command[IssueItem]("create") {
        description("Create a new Issue")
        val input = body[IssueCreateRequest]("Issue title, markdown body, labels and assignees")
        handle { service.issueCreate(cwd.value, input.value) }
      }
      command[IssueItem]("update") {
        description("Update an Issue's title, body, state, stateReason, labels or assignees")
        val input = body[IssueUpdateRequest]("Issue number and fields to update")
        handle { service.issueUpdate(cwd.value, input.value) }
      }
      command[IssueComment]("comment") {
        description("Post a comment on an Issue or Pull Request")
        val input = body[IssueCommentRequest]("Issue/PR number and markdown comment body")
        handle { service.issueComment(cwd.value, input.value) }
      }
    }

    commands("run") {
      description("Monitor, read logs, rerun, cancel and dispatch GitHub Actions workflow runs")
      val cwd = param[Option[String]]("cwd", "Caller working directory for automatic repository inference")
      command[Vector[WorkflowRun]]("list") {
        description("List recent GitHub Actions workflow runs")
        val branch = param[Option[String]]("branch", "Filter by branch name")
        val workflow = param[Option[String]]("workflow", "Optional workflow file name (e.g. ci.yml) or workflow ID")
        val event = param[Option[String]]("event", "Filter by trigger event")
        val status = param[Option[String]]("status", "Filter by status or conclusion")
        val actor = param[Option[String]]("actor", "Filter by triggering user login")
        val limit = param[Int]("limit", "Maximum runs to return") { default(20); minimum(1); maximum(100) }
        val repo = param[Option[String]]("repo", "Target owner/repo; inferred from cwd when omitted")
        handle { service.runList(branch.value, workflow.value, event.value, status.value, actor.value, limit.value, repo.value, cwd.value) }
      }
      command[WorkflowRun]("get") {
        description("Read a workflow run and all of its jobs and steps")
        val runId = param[Long]("runId", "Workflow run ID") { minimum(1L) }
        val repo = param[Option[String]]("repo", "Target owner/repo; inferred from cwd when omitted")
        handle { service.runGet(runId.value, repo.value, cwd.value) }
      }
      command[PlainText]("logs") {
        description("Fetch tail lines of workflow job logs (defaults to failed jobs only for compact diagnostics)")
        val runId = param[Option[Long]]("runId", "Workflow run ID")
        val jobId = param[Option[Long]]("jobId", "Specific workflow job ID")
        val failedOnly = param[Boolean]("failedOnly", "When using runId, fetch logs only for failed jobs") { default(true) }
        val tailLines = param[Int]("tailLines", "Number of trailing log lines per job") { default(120); minimum(1); maximum(2000) }
        val repo = param[Option[String]]("repo", "Target owner/repo; inferred from cwd when omitted")
        handle { service.runLogs(runId.value, jobId.value, failedOnly.value, tailLines.value, repo.value, cwd.value) }
      }
      command[ActionAck]("rerun") {
        description("Rerun failed jobs (default) or all jobs of a workflow run")
        val runId = param[Long]("runId", "Workflow run ID") { minimum(1L) }
        val failedOnly = param[Boolean]("failedOnly", "Rerun only failed jobs instead of the entire workflow") { default(true) }
        val repo = param[Option[String]]("repo", "Target owner/repo; inferred from cwd when omitted")
        handle { service.runRerun(runId.value, failedOnly.value, repo.value, cwd.value) }
      }
      command[ActionAck]("cancel") {
        description("Cancel an in-progress or queued workflow run")
        val runId = param[Long]("runId", "Workflow run ID") { minimum(1L) }
        val repo = param[Option[String]]("repo", "Target owner/repo; inferred from cwd when omitted")
        handle { service.runCancel(runId.value, repo.value, cwd.value) }
      }
      command[ActionAck]("dispatch") {
        description("Trigger a manual workflow_dispatch event on a branch or tag")
        val input = body[WorkflowDispatchRequest]("Workflow file name or ID, target git ref and optional string inputs")
        handle { service.runDispatch(cwd.value, input.value) }
      }
    }

    commands("release") {
      description("List, inspect and publish GitHub Releases")
      val cwd = param[Option[String]]("cwd", "Caller working directory for automatic repository inference")
      command[Vector[ReleaseInfo]]("list") {
        description("List repository Releases")
        val limit = param[Int]("limit", "Maximum Releases to return") { default(20); minimum(1); maximum(100) }
        val includeDrafts = param[Boolean]("includeDrafts", "Include unpublished draft Releases") { default(false) }
        val repo = param[Option[String]]("repo", "Target owner/repo; inferred from cwd when omitted")
        handle { service.releaseList(limit.value, includeDrafts.value, repo.value, cwd.value) }
      }
      command[ReleaseInfo]("get") {
        description("Read a Release by tag name, or omit tag to read the latest published Release")
        val tag = param[Option[String]]("tag", "Release tag name (omit or pass 'latest' for latest published Release)")
        val repo = param[Option[String]]("repo", "Target owner/repo; inferred from cwd when omitted")
        handle { service.releaseGet(tag.value, repo.value, cwd.value) }
      }
      command[ReleaseInfo]("create") {
        description("Create or publish a GitHub Release")
        val input = body[ReleaseCreateRequest]("Release tagName, optional title/body/targetCommitish, draft/prerelease/generateReleaseNotes flags")
        handle { service.releaseCreate(cwd.value, input.value) }
      }
    }
  }
