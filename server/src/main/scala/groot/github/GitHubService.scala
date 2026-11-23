package groot.github

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import framework.rpc.{PlainText, SSE}
import groot.schedule.Store
import io.circe.{ACursor, HCursor, Json}
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import org.http4s.Uri
import org.http4s.ember.client.EmberClientBuilder
import scala.concurrent.duration.*

final class GitHubService(val auth: GitHubAuth, val client: GitHubClient):
  import GitHubService.*

  private def withRepo[A](repo: Option[String], cwd: Option[String])(f: (String, RepoRef) => IO[A]): IO[A] =
    for token <- auth.requireToken; ref <- RepoResolver.resolve(repo, cwd); res <- f(token, ref) yield res
  private def repoBase(ref: RepoRef): String = s"/repos/${enc(ref.owner)}/${enc(ref.repo)}"

  def login(token: Option[String], scopes: String, clientId: Option[String]): IO[SSE[PlainText]] =
    auth.login(token, scopes, clientId).map(SSE(_))
  def logout: IO[LogoutResult] = auth.logout

  def whoami: IO[WhoamiResult] = auth.requireToken.flatMap(client.getJson(_, "/user")).flatMap { (hdrs, json) =>
    val c = json.hcursor
    IO.fromEither(c.get[String]("login")).adaptError(_ => GitHubFailure("github_upstream", "Missing login in /user")).map { login =>
      val rl = for
        lim <- hdrs.get("x-ratelimit-limit").flatMap(_.toIntOption)
        rem <- hdrs.get("x-ratelimit-remaining").flatMap(_.toIntOption)
        rst <- hdrs.get("x-ratelimit-reset").flatMap(_.toLongOption)
        used = hdrs.get("x-ratelimit-used").flatMap(_.toIntOption).getOrElse(math.max(0, lim - rem))
      yield RateLimitInfo(lim, rem, used, Instant.ofEpochSecond(rst).toString)
      WhoamiResult(login, c.get[Long]("id").getOrElse(0L), opt(c, "name"), opt(c, "email"), GitHubAuth.parseScopes(hdrs), rl)
    }
  }

  def api(cwd: Option[String], req: ApiRequest): IO[ApiResponse] =
    for
      token <- auth.requireToken
      path <- if req.path.contains("{owner}") || req.path.contains("{repo}") then
        RepoResolver.resolve(req.repo, cwd).map(r => req.path.replace("{owner}", enc(r.owner)).replace("{repo}", enc(r.repo)))
      else IO.pure(req.path)
      res <- if req.paginate && req.method.trim.equalsIgnoreCase("GET") then
        val q = if req.query.contains("per_page") then req.query else req.query + ("per_page" -> "100")
        client.paginateArray(token, path, q, req.maxPages).map((h, vs) => ApiResponse(200, keepHeaders(h), Json.fromValues(vs)))
      else client.sendJson(token, req.method, path, req.query, req.body, req.headers).map((st, h, b) => ApiResponse(st, keepHeaders(h), b))
    yield res

  def graphql(req: GraphqlRequest): IO[Json] =
    auth.requireToken.flatMap(client.graphql(_, req.query, req.variables, req.operationName))

  def notifications(all: Boolean, participating: Boolean, repo: Option[String], since: Option[String], limit: Int, markRead: Boolean): IO[Vector[NotificationSummary]] =
    for
      token <- auth.requireToken
      path <- repo.map(_.trim).filter(_.nonEmpty).fold(IO.pure("/notifications"))(r => IO(RepoResolver.parseExplicit(r)).map(ref => s"${repoBase(ref)}/notifications"))
      lim = clamp(limit, 1, 100)
      q = Map("all" -> all.toString, "participating" -> participating.toString, "per_page" -> lim.toString) ++ optParam("since", since)
      (_, json) <- client.getJson(token, path, q)
      _ <- if markRead then client.sendJson(token, "PUT", path, body = Some(Json.obj())).void else IO.unit
    yield arr(json).take(lim).map { item =>
      val c = item.hcursor; val subj = c.downField("subject")
      NotificationSummary(str(c, "id"), bool(c, "unread"), str(c, "reason"), str(c, "updated_at"),
        str(c.downField("repository"), "full_name"), str(subj, "type"), str(subj, "title"),
        opt(subj, "url").flatMap(_.split("/").lastOption).flatMap(_.toIntOption))
    }

  def search(kind: SearchKind, query: String, sort: Option[String], order: Option[String], limit: Int): IO[SearchResult] =
    val q0 = query.trim
    if q0.isEmpty then IO.raiseError(GitHubFailure("invalid_github", "Search query must not be blank"))
    else auth.requireToken.flatMap { token =>
      val lim = clamp(limit, 1, 100)
      val (ep, q) = kind match
        case SearchKind.repos => "/search/repositories" -> q0
        case SearchKind.code => "/search/code" -> q0
        case SearchKind.commits => "/search/commits" -> q0
        case SearchKind.prs => "/search/issues" -> (if q0.contains("is:pr") || q0.contains("type:pr") then q0 else s"$q0 is:pr")
        case SearchKind.issues => "/search/issues" -> (if q0.contains("is:issue") || q0.contains("type:issue") || q0.contains("is:pr") then q0 else s"$q0 is:issue")
      val params = Map("q" -> q, "per_page" -> lim.toString) ++ optParam("sort", sort) ++ optParam("order", order)
      client.getJson(token, ep, params).map { (_, json) =>
        val c = json.hcursor
        SearchResult(int(c, "total_count"), bool(c, "incomplete_results"),
          arr(c.downField("items").focus.getOrElse(Json.arr())).take(lim).map(i => parseSearchHit(kind, i.hcursor)))
      }
    }

  def repoGet(repo: Option[String], cwd: Option[String]): IO[RepoDetail] = withRepo(repo, cwd) { (token, ref) =>
    client.getJson(token, repoBase(ref)).map { (_, json) =>
      val c = json.hcursor
      RepoDetail(str(c, "full_name", ref.fullName), opt(c, "description"), bool(c, "private"), bool(c, "fork"),
        bool(c, "archived"), str(c, "default_branch", "main"), opt(c, "language"),
        arr(c.downField("topics").focus.getOrElse(Json.arr())).flatMap(_.asString),
        int(c, "stargazers_count"), int(c, "forks_count"), int(c, "open_issues_count"),
        opt(c, "pushed_at"), opt(c, "updated_at"), str(c, "html_url"), opt(c.downField("parent"), "full_name"))
    }
  }

  def repoRead(path: String, refName: Option[String], repo: Option[String], cwd: Option[String]): IO[RepoContent] = withRepo(repo, cwd) { (token, ref) =>
    val clean = path.trim.stripPrefix("/").stripSuffix("/")
    val suffix = if clean.isEmpty then "" else "/" + clean.split("/").filter(_.nonEmpty).map(enc).mkString("/")
    client.getJson(token, s"${repoBase(ref)}/contents$suffix", optParam("ref", refName)).map { (_, json) =>
      json.asArray match
        case Some(entries) =>
          RepoContent(ref.fullName, clean, "dir", entries = entries.map { e =>
            val c = e.hcursor; RepoContentEntry(str(c, "name"), str(c, "path"), str(c, "type"), long(c, "size"), str(c, "sha"))
          })
        case None =>
          val c = json.hcursor
          val (utf8, b64) = opt(c, "content").map(_.replaceAll("\\s+", "")).filter(_.nonEmpty) match
            case Some(raw) => decodeUtf8(Base64.getDecoder.decode(raw)).fold((Option.empty[String], Some(raw)))(t => (Some(t), None))
            case None => (None, None)
          RepoContent(ref.fullName, str(c, "path", clean), str(c, "type", "file"), long(c, "size"), opt(c, "sha"), utf8, b64, url = opt(c, "html_url"))
    }
  }

  def prList(state: String, head: Option[String], base: Option[String], author: Option[String], label: Option[String],
      limit: Int, repo: Option[String], cwd: Option[String]): IO[Vector[PrSummary]] = withRepo(repo, cwd) { (token, ref) =>
    parsePrStates(state).flatMap { states =>
      val lim = clamp(limit, 1, 100); val cleanAuthor = cleanOpt(author)
      val vars = Map("owner" -> Json.fromString(ref.owner), "name" -> Json.fromString(ref.repo),
        "first" -> Json.fromInt(if cleanAuthor.isDefined then math.min(100, lim * 3) else lim),
        "states" -> Json.fromValues(states.map(Json.fromString))) ++
        cleanOpt(head).map(h => "head" -> Json.fromString(h.split(":").last)) ++
        cleanOpt(base).map("base" -> Json.fromString(_)) ++
        cleanOpt(label).map(l => "labels" -> Json.arr(Json.fromString(l)))
      client.graphql(token, prListQuery, vars).map { data =>
        val mapped = arr(data.hcursor.downField("repository").downField("pullRequests").downField("nodes").focus.getOrElse(Json.arr())).map(n => parsePrSummaryGql(n.hcursor))
        cleanAuthor.fold(mapped)(a => mapped.filter(_.author.exists(_.equalsIgnoreCase(a)))).take(lim)
      }
    }
  }

  def prGet(number: Int, repo: Option[String], cwd: Option[String]): IO[PrDetail] = withRepo(repo, cwd) { (token, ref) =>
    client.graphql(token, prGetQuery, Map("owner" -> Json.fromString(ref.owner), "name" -> Json.fromString(ref.repo), "number" -> Json.fromInt(number))).flatMap { data =>
      IO.fromOption(data.hcursor.downField("repository").downField("pullRequest").focus.filterNot(_.isNull))(
        GitHubFailure("github_not_found", s"Pull request #$number not found in ${ref.fullName}")).map(j => parsePrDetailGql(j.hcursor))
    }
  }

  def prDiff(number: Int, filesOnly: Boolean, maxBytes: Int, repo: Option[String], cwd: Option[String]): IO[PrDiffResult] = withRepo(repo, cwd) { (token, ref) =>
    for
      (_, filesJson) <- client.paginateArray(token, s"${repoBase(ref)}/pulls/$number/files", Map("per_page" -> "100"), maxPages = 5)
      files = filesJson.map { f => val c = f.hcursor; PrChangedFile(str(c, "filename"), str(c, "status"), int(c, "additions"), int(c, "deletions"), opt(c, "previous_filename")) }
      (diff, trunc) <- if filesOnly then IO.pure(None -> false) else
        client.getText(token, s"${repoBase(ref)}/pulls/$number", "application/vnd.github.v3.diff", clamp(maxBytes, 1024, GitHubClient.maxRawBytes)).map((t, tr) => Some(t) -> tr)
    yield PrDiffResult(number, files, diff, trunc)
  }

  def prComments(number: Int, unresolvedOnly: Boolean, limit: Int, repo: Option[String], cwd: Option[String]): IO[PrCommentsResult] = withRepo(repo, cwd) { (token, ref) =>
    val vars = Map("owner" -> Json.fromString(ref.owner), "name" -> Json.fromString(ref.repo), "number" -> Json.fromInt(number), "limit" -> Json.fromInt(clamp(limit, 1, 100)))
    client.graphql(token, prCommentsQuery, vars).flatMap { data =>
      IO.fromOption(data.hcursor.downField("repository").downField("pullRequest").focus.filterNot(_.isNull))(
        GitHubFailure("github_not_found", s"Pull request #$number not found in ${ref.fullName}")).map { prJson =>
        val pc = prJson.hcursor
        val comments = arr(pc.downField("comments").downField("nodes").focus.getOrElse(Json.arr())).map { n =>
          val c = n.hcursor; IssueComment(long(c, "databaseId"), opt(c.downField("author"), "login"), str(c, "body"), str(c, "createdAt"), opt(c, "updatedAt"), str(c, "url"))
        }
        val threads = arr(pc.downField("reviewThreads").downField("nodes").focus.getOrElse(Json.arr())).map { t =>
          val c = t.hcursor
          val items = arr(c.downField("comments").downField("nodes").focus.getOrElse(Json.arr())).map { rc =>
            val r = rc.hcursor; ReviewCommentItem(opt(r.downField("author"), "login"), str(r, "body"), str(r, "createdAt"), str(r, "url"))
          }
          PrReviewThread(str(c, "id"), str(c, "path"), c.get[Option[Int]]("line").toOption.flatten, bool(c, "isResolved"), bool(c, "isOutdated"), items)
        }
        PrCommentsResult(number, comments, if unresolvedOnly then threads.filterNot(_.isResolved) else threads)
      }
    }
  }

  def prCreate(cwd: Option[String], req: PrCreateRequest): IO[PrSummary] =
    if req.title.trim.isEmpty || req.head.trim.isEmpty then IO.raiseError(GitHubFailure("invalid_github", "PR title and head branch must not be blank"))
    else withRepo(req.repo, cwd) { (token, ref) =>
      for
        base <- cleanOpt(req.base).fold(repoGet(Some(ref.fullName), None).map(_.defaultBranch))(IO.pure)
        body = Json.obj("title" -> Json.fromString(req.title.trim), "head" -> Json.fromString(req.head.trim), "base" -> Json.fromString(base),
          "body" -> Json.fromString(req.body.getOrElse("")), "draft" -> Json.fromBoolean(req.draft), "maintainer_can_modify" -> Json.fromBoolean(req.maintainerCanModify))
        (_, _, created) <- client.sendJson(token, "POST", s"${repoBase(ref)}/pulls", body = Some(body))
        num = int(created.hcursor, "number")
        _ <- if req.labels.nonEmpty && num > 0 then
          client.sendJson(token, "POST", s"${repoBase(ref)}/issues/$num/labels", body = Some(Json.obj("labels" -> Json.fromValues(req.labels.map(Json.fromString))))).void
        else IO.unit
        c = created.hcursor
        labels = if req.labels.nonEmpty then req.labels else labelNames(c)
      yield PrSummary(num, str(c, "title"), str(c, "state").toUpperCase(java.util.Locale.ROOT), bool(c, "draft"),
        opt(c.downField("user"), "login"), str(c.downField("head"), "ref"), str(c.downField("base"), "ref"), labels, None, None, str(c, "updated_at"), str(c, "html_url"))
    }

  def prUpdate(cwd: Option[String], req: PrUpdateRequest): IO[PrDetail] = withRepo(req.repo, cwd) { (token, ref) =>
    val patch = jsonObj("title" -> req.title.map(Json.fromString), "body" -> req.body.map(Json.fromString),
      "state" -> cleanOpt(req.state).map(s => Json.fromString(s.toLowerCase(java.util.Locale.ROOT))), "base" -> cleanOpt(req.base).map(Json.fromString))
    for
      _ <- if patch.asObject.exists(_.nonEmpty) then client.sendJson(token, "PATCH", s"${repoBase(ref)}/pulls/${req.number}", body = Some(patch)).void else IO.unit
      _ <- req.draft.traverse_ { wantDraft =>
        client.getJson(token, s"${repoBase(ref)}/pulls/${req.number}").flatMap { (_, prJson) =>
          val curDraft = bool(prJson.hcursor, "draft")
          val nodeId = str(prJson.hcursor, "node_id")
          if wantDraft == curDraft || nodeId.isEmpty then IO.unit
          else
            val mut = if wantDraft then "convertPullRequestToDraft" else "markPullRequestReadyForReview"
            client.graphql(token, s"mutation($$id: ID!) { $mut(input: { pullRequestId: $$id }) { pullRequest { isDraft } } }", Map("id" -> Json.fromString(nodeId))).void
        }
      }
      _ <- if req.addLabels.nonEmpty then
        client.sendJson(token, "POST", s"${repoBase(ref)}/issues/${req.number}/labels", body = Some(Json.obj("labels" -> Json.fromValues(req.addLabels.map(Json.fromString))))).void
      else IO.unit
      _ <- req.removeLabels.traverse_(l => client.sendJson(token, "DELETE", s"${repoBase(ref)}/issues/${req.number}/labels/${enc(l)}").attempt.void)
      updated <- prGet(req.number, Some(ref.fullName), None)
    yield updated
  }

  def prReview(cwd: Option[String], req: PrReviewRequest): IO[PrReviewReceipt] =
    val ev = req.event.trim.toUpperCase(java.util.Locale.ROOT)
    if !Set("COMMENT", "APPROVE", "REQUEST_CHANGES")(ev) then IO.raiseError(GitHubFailure("invalid_github", s"Invalid review event '${req.event}'"))
    else if (ev == "COMMENT" || ev == "REQUEST_CHANGES") && req.body.forall(_.trim.isEmpty) then
      IO.raiseError(GitHubFailure("invalid_github", s"Review event $ev requires a non-empty body"))
    else withRepo(req.repo, cwd) { (token, ref) =>
      val payload = jsonObj("event" -> Some(Json.fromString(ev)), "body" -> req.body.map(Json.fromString))
      client.sendJson(token, "POST", s"${repoBase(ref)}/pulls/${req.number}/reviews", body = Some(payload)).map { (_, _, j) =>
        val c = j.hcursor; PrReviewReceipt(long(c, "id"), str(c, "state", ev), opt(c.downField("user"), "login"), opt(c, "submitted_at"), str(c, "html_url"))
      }
    }

  def prMerge(number: Int, method: String, commitTitle: Option[String], commitMessage: Option[String],
      sha: Option[String], deleteBranch: Boolean, repo: Option[String], cwd: Option[String]): IO[PrMergeResult] =
    val m = method.trim.toLowerCase(java.util.Locale.ROOT)
    if !Set("merge", "squash", "rebase")(m) then IO.raiseError(GitHubFailure("invalid_github", s"Invalid merge method '$method'"))
    else withRepo(repo, cwd) { (token, ref) =>
      for
        headBranch <- if deleteBranch then client.getJson(token, s"${repoBase(ref)}/pulls/$number").map { (_, j) =>
          val head = j.hcursor.downField("head"); val hr = opt(head, "ref")
          if opt(head.downField("repo"), "full_name").exists(_.equalsIgnoreCase(ref.fullName)) && hr != opt(j.hcursor.downField("base"), "ref") then hr else None
        } else IO.pure(None)
        body = jsonObj("merge_method" -> Some(Json.fromString(m)), "commit_title" -> cleanOpt(commitTitle).map(Json.fromString),
          "commit_message" -> commitMessage.map(Json.fromString), "sha" -> cleanOpt(sha).map(Json.fromString))
        (_, _, mj) <- client.sendJson(token, "PUT", s"${repoBase(ref)}/pulls/$number/merge", body = Some(body))
        mc = mj.hcursor; merged = bool(mc, "merged")
        deleted <- if merged && headBranch.isDefined then
          val refPath = headBranch.get.split("/").filter(_.nonEmpty).map(enc).mkString("/")
          client.sendJson(token, "DELETE", s"${repoBase(ref)}/git/refs/heads/$refPath").as(true).handleError(_ => false)
        else IO.pure(false)
      yield PrMergeResult(merged, opt(mc, "sha"), str(mc, "message"), deleted)
    }

  def issueList(state: String, labels: Option[String], assignee: Option[String], creator: Option[String],
      limit: Int, repo: Option[String], cwd: Option[String]): IO[Vector[IssueItem]] =
    val st = state.trim.toLowerCase(java.util.Locale.ROOT)
    if !Set("open", "closed", "all")(st) then IO.raiseError(GitHubFailure("invalid_github", s"Invalid issue state '$state'"))
    else withRepo(repo, cwd) { (token, ref) =>
      val lim = clamp(limit, 1, 100)
      val q = Map("state" -> st, "per_page" -> math.min(100, lim * 2).toString) ++ optParam("labels", labels) ++ optParam("assignee", assignee) ++ optParam("creator", creator)
      client.paginateArray(token, s"${repoBase(ref)}/issues", q, maxPages = 3).map { (_, items) =>
        items.filter(_.hcursor.downField("pull_request").focus.isEmpty).take(lim).map(i => parseIssue(i.hcursor, includeBody = false))
      }
    }

  def issueGet(number: Int, commentsLimit: Int, repo: Option[String], cwd: Option[String]): IO[IssueItem] = withRepo(repo, cwd) { (token, ref) =>
    val lim = clamp(commentsLimit, 0, 100)
    val cCall = if lim == 0 then IO.pure(Map.empty[String, String] -> Json.arr()) else client.getJson(token, s"${repoBase(ref)}/issues/$number/comments", Map("per_page" -> lim.toString))
    (client.getJson(token, s"${repoBase(ref)}/issues/$number"), cCall).parTupled.map { (iRes, cRes) =>
      parseIssue(iRes._2.hcursor, includeBody = true, arr(cRes._2).take(lim).map(cm => parseIssueComment(cm.hcursor)))
    }
  }

  def issueCreate(cwd: Option[String], req: IssueCreateRequest): IO[IssueItem] =
    if req.title.trim.isEmpty then IO.raiseError(GitHubFailure("invalid_github", "Issue title must not be blank"))
    else withRepo(req.repo, cwd) { (token, ref) =>
      val body = jsonObj("title" -> Some(Json.fromString(req.title.trim)), "body" -> req.body.map(Json.fromString),
        "labels" -> Option.when(req.labels.nonEmpty)(Json.fromValues(req.labels.map(Json.fromString))),
        "assignees" -> Option.when(req.assignees.nonEmpty)(Json.fromValues(req.assignees.map(Json.fromString))))
      client.sendJson(token, "POST", s"${repoBase(ref)}/issues", body = Some(body)).map((_, _, j) => parseIssue(j.hcursor, includeBody = false))
    }

  def issueUpdate(cwd: Option[String], req: IssueUpdateRequest): IO[IssueItem] = withRepo(req.repo, cwd) { (token, ref) =>
    val body = jsonObj("title" -> req.title.map(Json.fromString), "body" -> req.body.map(Json.fromString),
      "state" -> cleanOpt(req.state).map(s => Json.fromString(s.toLowerCase(java.util.Locale.ROOT))),
      "state_reason" -> cleanOpt(req.stateReason).map(Json.fromString),
      "labels" -> req.labels.map(ls => Json.fromValues(ls.map(Json.fromString))),
      "assignees" -> req.assignees.map(as => Json.fromValues(as.map(Json.fromString))))
    if body.asObject.forall(_.isEmpty) then IO.raiseError(GitHubFailure("invalid_github", "issue.update requires at least one field"))
    else client.sendJson(token, "PATCH", s"${repoBase(ref)}/issues/${req.number}", body = Some(body)).map((_, _, j) => parseIssue(j.hcursor, includeBody = false))
  }

  def issueComment(cwd: Option[String], req: IssueCommentRequest): IO[IssueComment] =
    if req.body.trim.isEmpty then IO.raiseError(GitHubFailure("invalid_github", "Comment body must not be blank"))
    else withRepo(req.repo, cwd) { (token, ref) =>
      client.sendJson(token, "POST", s"${repoBase(ref)}/issues/${req.number}/comments", body = Some(Json.obj("body" -> Json.fromString(req.body)))).map((_, _, j) => parseIssueComment(j.hcursor))
    }

  def runList(branch: Option[String], workflow: Option[String], event: Option[String], status: Option[String],
      actor: Option[String], limit: Int, repo: Option[String], cwd: Option[String]): IO[Vector[WorkflowRun]] = withRepo(repo, cwd) { (token, ref) =>
    val lim = clamp(limit, 1, 100)
    val path = cleanOpt(workflow).fold(s"${repoBase(ref)}/actions/runs")(wf => s"${repoBase(ref)}/actions/workflows/${enc(wf)}/runs")
    val q = Map("per_page" -> lim.toString) ++ optParam("branch", branch) ++ optParam("event", event) ++ optParam("status", status) ++ optParam("actor", actor)
    client.getJson(token, path, q).map((_, j) => arr(j.hcursor.downField("workflow_runs").focus.getOrElse(Json.arr())).take(lim).map(r => parseRun(r.hcursor)))
  }

  def runGet(runId: Long, repo: Option[String], cwd: Option[String]): IO[WorkflowRun] = withRepo(repo, cwd) { (token, ref) =>
    (client.getJson(token, s"${repoBase(ref)}/actions/runs/$runId"),
      client.getJson(token, s"${repoBase(ref)}/actions/runs/$runId/jobs", Map("per_page" -> "100"))).parTupled.map { (rRes, jRes) =>
      parseRun(rRes._2.hcursor, arr(jRes._2.hcursor.downField("jobs").focus.getOrElse(Json.arr())).map(j => parseJob(j.hcursor)))
    }
  }

  def runLogs(runId: Option[Long], jobId: Option[Long], failedOnly: Boolean, tailLines: Int, repo: Option[String], cwd: Option[String]): IO[PlainText] =
    val lines = clamp(tailLines, 1, 2000)
    (runId, jobId) match
      case (_, Some(jid)) => withRepo(repo, cwd)((tok, ref) => client.getText(tok, s"${repoBase(ref)}/actions/jobs/$jid/logs", maxBytes = GitHubClient.maxRawBytes).map((t, _) => PlainText(tail(t, lines))))
      case (Some(rid), None) => withRepo(repo, cwd) { (tok, ref) =>
        runGet(rid, Some(ref.fullName), None).flatMap { detail =>
          val jobs = (if failedOnly then detail.jobs.filter(_.conclusion.exists(Set("failure", "timed_out", "startup_failure", "action_required"))) else detail.jobs).take(5)
          if jobs.isEmpty then IO.pure(PlainText(if failedOnly then s"Run #$rid (${detail.status}/${detail.conclusion.getOrElse("pending")}) has no failed jobs." else s"Run #$rid has no jobs."))
          else jobs.traverse { j =>
            client.getText(tok, s"${repoBase(ref)}/actions/jobs/${j.id}/logs", maxBytes = 512 * 1024)
              .map((t, _) => s"=== Job: ${j.name} (id=${j.id}, conclusion=${j.conclusion.getOrElse(j.status)}) ===\n${tail(t, lines)}")
              .handleError(e => s"=== Job: ${j.name} (id=${j.id}) ===\n[log unavailable: ${e.getMessage}]")
          }.map(parts => PlainText(parts.mkString("\n\n")))
        }
      }
      case (None, None) => IO.raiseError(GitHubFailure("invalid_github", "Specify runId or jobId to fetch workflow logs"))

  def runRerun(runId: Long, failedOnly: Boolean, repo: Option[String], cwd: Option[String]): IO[ActionAck] = withRepo(repo, cwd) { (token, ref) =>
    val action = if failedOnly then "rerun-failed-jobs" else "rerun"
    client.sendJson(token, "POST", s"${repoBase(ref)}/actions/runs/$runId/$action").as(ActionAck(true, s"Triggered $action for run $runId in ${ref.fullName}"))
  }

  def runCancel(runId: Long, repo: Option[String], cwd: Option[String]): IO[ActionAck] = withRepo(repo, cwd) { (token, ref) =>
    client.sendJson(token, "POST", s"${repoBase(ref)}/actions/runs/$runId/cancel").as(ActionAck(true, s"Requested cancellation for run $runId in ${ref.fullName}"))
  }

  def runDispatch(cwd: Option[String], req: WorkflowDispatchRequest): IO[ActionAck] =
    if req.workflow.trim.isEmpty || req.ref.trim.isEmpty then IO.raiseError(GitHubFailure("invalid_github", "workflow and ref must not be blank"))
    else withRepo(req.repo, cwd) { (token, ref) =>
      val body = jsonObj("ref" -> Some(Json.fromString(req.ref.trim)), "inputs" -> Option.when(req.inputs.nonEmpty)(Json.fromFields(req.inputs.view.mapValues(Json.fromString))))
      client.sendJson(token, "POST", s"${repoBase(ref)}/actions/workflows/${enc(req.workflow.trim)}/dispatches", body = Some(body))
        .as(ActionAck(true, s"Dispatched '${req.workflow.trim}' on '${req.ref.trim}' in ${ref.fullName}"))
    }

  def releaseList(limit: Int, includeDrafts: Boolean, repo: Option[String], cwd: Option[String]): IO[Vector[ReleaseInfo]] = withRepo(repo, cwd) { (token, ref) =>
    val lim = clamp(limit, 1, 100)
    client.getJson(token, s"${repoBase(ref)}/releases", Map("per_page" -> lim.toString)).map { (_, j) =>
      val all = arr(j).map(r => parseRelease(r.hcursor, full = false))
      (if includeDrafts then all else all.filterNot(_.isDraft)).take(lim)
    }
  }

  def releaseGet(tag: Option[String], repo: Option[String], cwd: Option[String]): IO[ReleaseInfo] = withRepo(repo, cwd) { (token, ref) =>
    val ep = cleanOpt(tag).filterNot(_.equalsIgnoreCase("latest")).fold(s"${repoBase(ref)}/releases/latest")(t => s"${repoBase(ref)}/releases/tags/${enc(t)}")
    client.getJson(token, ep).map((_, j) => parseRelease(j.hcursor, full = true))
  }

  def releaseCreate(cwd: Option[String], req: ReleaseCreateRequest): IO[ReleaseInfo] =
    if req.tagName.trim.isEmpty then IO.raiseError(GitHubFailure("invalid_github", "Release tagName must not be blank"))
    else withRepo(req.repo, cwd) { (token, ref) =>
      val body = jsonObj("tag_name" -> Some(Json.fromString(req.tagName.trim)), "name" -> req.name.map(Json.fromString),
        "body" -> req.body.map(Json.fromString), "target_commitish" -> cleanOpt(req.targetCommitish).map(Json.fromString),
        "draft" -> Some(Json.fromBoolean(req.draft)), "prerelease" -> Some(Json.fromBoolean(req.prerelease)),
        "generate_release_notes" -> Some(Json.fromBoolean(req.generateReleaseNotes)))
      client.sendJson(token, "POST", s"${repoBase(ref)}/releases", body = Some(body)).map((_, _, j) => parseRelease(j.hcursor, full = true))
    }

object GitHubService:
  def resource(
      home: Path = Store.defaultHome,
      apiBase: Uri = GitHubClient.defaultApiBase,
      oauthBase: Uri = GitHubClient.defaultOAuthBase,
      config: IO[GitHubConfig] = IO.pure(GitHubConfig())
  ): Resource[IO, GitHubService] =
    for
      http <- EmberClientBuilder.default[IO].withTimeout(Duration.Inf).withMaxResponseHeaderSize(64 * 1024).build
      ghClient = new GitHubClient(http, apiBase, oauthBase, 30.seconds, config)
      auth <- GitHubAuth.resource(home, ghClient, config = config)
    yield new GitHubService(auth, ghClient)

  private val prListQuery =
    """query($owner: String!, $name: String!, $first: Int!, $states: [PullRequestState!], $head: String, $base: String, $labels: [String!]) {
      |  repository(owner: $owner, name: $name) {
      |    pullRequests(first: $first, states: $states, headRefName: $head, baseRefName: $base, labels: $labels, orderBy: {field: UPDATED_AT, direction: DESC}) {
      |      nodes { number title state isDraft headRefName baseRefName reviewDecision updatedAt url author { login }
      |        labels(first: 20) { nodes { name } } commits(last: 1) { nodes { commit { statusCheckRollup { state } } } } }
      |    }
      |  }
      |}""".stripMargin

  private val prGetQuery =
    """query($owner: String!, $name: String!, $number: Int!) {
      |  repository(owner: $owner, name: $name) {
      |    pullRequest(number: $number) {
      |      number title state isDraft headRefName headRefOid baseRefName mergeable mergeStateStatus reviewDecision
      |      additions deletions changedFiles body createdAt updatedAt mergedAt url author { login } mergedBy { login }
      |      labels(first: 30) { nodes { name } } comments { totalCount } reviewThreads(first: 100) { nodes { isResolved } }
      |      commits(last: 1) { nodes { commit { statusCheckRollup { state contexts(first: 100) { nodes {
      |        __typename ... on CheckRun { name status conclusion detailsUrl } ... on StatusContext { context state targetUrl }
      |      } } } } } }
      |    }
      |  }
      |}""".stripMargin

  private val prCommentsQuery =
    """query($owner: String!, $name: String!, $number: Int!, $limit: Int!) {
      |  repository(owner: $owner, name: $name) {
      |    pullRequest(number: $number) {
      |      comments(last: $limit) { nodes { databaseId body createdAt updatedAt url author { login } } }
      |      reviewThreads(last: $limit) { nodes { id path line isResolved isOutdated comments(first: 30) { nodes { body createdAt url author { login } } } } }
      |    }
      |  }
      |}""".stripMargin

  private def parsePrStates(raw: String): IO[Vector[String]] = raw.trim.toLowerCase(java.util.Locale.ROOT) match
    case "open" => IO.pure(Vector("OPEN"))
    case "closed" => IO.pure(Vector("CLOSED", "MERGED"))
    case "merged" => IO.pure(Vector("MERGED"))
    case "all" => IO.pure(Vector("OPEN", "CLOSED", "MERGED"))
    case other => IO.raiseError(GitHubFailure("invalid_github", s"Invalid PR state '$other': use open, closed, merged or all"))

  private def parsePrSummaryGql(c: HCursor): PrSummary =
    val rollup = arr(c.downField("commits").downField("nodes").focus.getOrElse(Json.arr())).headOption
      .flatMap(n => opt(n.hcursor.downField("commit").downField("statusCheckRollup"), "state"))
    val labels = arr(c.downField("labels").downField("nodes").focus.getOrElse(Json.arr())).flatMap(_.hcursor.get[String]("name").toOption)
    PrSummary(int(c, "number"), str(c, "title"), str(c, "state"), bool(c, "isDraft"), opt(c.downField("author"), "login"),
      str(c, "headRefName"), str(c, "baseRefName"), labels, opt(c, "reviewDecision"), rollup, str(c, "updatedAt"), str(c, "url"))

  private def parsePrDetailGql(c: HCursor): PrDetail =
    val rollupOpt = arr(c.downField("commits").downField("nodes").focus.getOrElse(Json.arr())).headOption.map(_.hcursor.downField("commit").downField("statusCheckRollup"))
    val items = rollupOpt.map(r => arr(r.downField("contexts").downField("nodes").focus.getOrElse(Json.arr()))).getOrElse(Vector.empty).flatMap { node =>
      val nc = node.hcursor
      str(nc, "__typename") match
        case "CheckRun" => Some(CheckItem(str(nc, "name"), str(nc, "status"), opt(nc, "conclusion"), opt(nc, "detailsUrl")))
        case "StatusContext" =>
          val st = str(nc, "state"); val pend = st == "PENDING" || st == "EXPECTED"
          Some(CheckItem(str(nc, "context"), if pend then "PENDING" else "COMPLETED", Option.when(!pend)(st), opt(nc, "targetUrl")))
        case _ => None
    }
    val pending = items.count(_.status != "COMPLETED")
    val passed = items.count(i => i.status == "COMPLETED" && i.conclusion.exists(v => Set("SUCCESS", "NEUTRAL", "SKIPPED")(v.toUpperCase(java.util.Locale.ROOT))))
    val failed = items.size - pending - passed
    val unresolved = arr(c.downField("reviewThreads").downField("nodes").focus.getOrElse(Json.arr())).count(t => !bool(t.hcursor, "isResolved"))
    val labels = arr(c.downField("labels").downField("nodes").focus.getOrElse(Json.arr())).flatMap(_.hcursor.get[String]("name").toOption)
    PrDetail(int(c, "number"), str(c, "title"), str(c, "state"), bool(c, "isDraft"), opt(c.downField("author"), "login"),
      str(c, "headRefName"), str(c, "headRefOid"), str(c, "baseRefName"), opt(c, "mergeable"), opt(c, "mergeStateStatus"),
      opt(c, "reviewDecision"), int(c, "additions"), int(c, "deletions"), int(c, "changedFiles"),
      int(c.downField("comments"), "totalCount"), unresolved, labels, str(c, "createdAt"), str(c, "updatedAt"),
      opt(c, "mergedAt"), opt(c.downField("mergedBy"), "login"), str(c, "url"), str(c, "body"),
      PrChecksSummary(rollupOpt.flatMap(opt(_, "state")), items.size, passed, failed, pending, items))

  private def parseIssue(c: HCursor, includeBody: Boolean, comments: Vector[IssueComment] = Vector.empty): IssueItem =
    val assignees = arr(c.downField("assignees").focus.getOrElse(Json.arr())).flatMap(_.hcursor.get[String]("login").toOption)
    IssueItem(int(c, "number"), str(c, "title"), str(c, "state"), opt(c, "state_reason"), opt(c.downField("user"), "login"),
      labelNames(c), assignees, int(c, "comments"), str(c, "created_at"), str(c, "updated_at"), opt(c, "closed_at"),
      str(c, "html_url"), Option.when(includeBody)(str(c, "body")), comments)

  private def parseIssueComment(c: HCursor): IssueComment =
    IssueComment(long(c, "id"), opt(c.downField("user"), "login"), str(c, "body"), str(c, "created_at"), opt(c, "updated_at"), str(c, "html_url"))

  private def parseRun(c: HCursor, jobs: Vector[WorkflowJob] = Vector.empty): WorkflowRun =
    WorkflowRun(long(c, "id"), str(c, "name", str(c, "display_title")), long(c, "workflow_id"), opt(c, "head_branch"),
      str(c, "head_sha"), str(c, "event"), str(c, "status"), opt(c, "conclusion"), int(c, "run_number"),
      c.get[Int]("run_attempt").getOrElse(1), opt(c.downField("actor"), "login"), str(c, "created_at"), str(c, "updated_at"), str(c, "html_url"), jobs)

  private def parseJob(c: HCursor): WorkflowJob =
    val steps = arr(c.downField("steps").focus.getOrElse(Json.arr())).map { s => val sc = s.hcursor; WorkflowStep(int(sc, "number"), str(sc, "name"), str(sc, "status"), opt(sc, "conclusion")) }
    val failed = steps.filter(_.conclusion.exists(v => !Set("success", "skipped", "neutral")(v))).map(_.name)
    WorkflowJob(long(c, "id"), str(c, "name"), str(c, "status"), opt(c, "conclusion"), opt(c, "started_at"), opt(c, "completed_at"), str(c, "html_url"), failed, steps)

  private def parseRelease(c: HCursor, full: Boolean): ReleaseInfo =
    val assets = if !full then Vector.empty else arr(c.downField("assets").focus.getOrElse(Json.arr())).map { a =>
      val ac = a.hcursor; ReleaseAsset(str(ac, "name"), long(ac, "size"), int(ac, "download_count"), str(ac, "content_type"), str(ac, "browser_download_url"))
    }
    ReleaseInfo(long(c, "id"), str(c, "tag_name"), opt(c, "name"), Option.when(full)(str(c, "target_commitish")),
      bool(c, "draft"), bool(c, "prerelease"), opt(c.downField("author"), "login"), str(c, "created_at"), opt(c, "published_at"),
      str(c, "html_url"), Option.when(full)(str(c, "body")), assets)

  private def parseSearchHit(kind: SearchKind, c: HCursor): SearchItem = kind match
    case SearchKind.repos => SearchItem("repo", opt(c, "full_name"), title = opt(c, "description").getOrElse(str(c, "full_name")),
      author = opt(c.downField("owner"), "login"), stars = c.get[Int]("stargazers_count").toOption, updatedAt = opt(c, "updated_at"), url = str(c, "html_url"))
    case SearchKind.code => SearchItem("code", opt(c.downField("repository"), "full_name"), title = str(c, "name"),
      path = opt(c, "path"), sha = opt(c, "sha"), url = str(c, "html_url"))
    case SearchKind.commits => SearchItem("commit", opt(c.downField("repository"), "full_name"),
      title = str(c.downField("commit"), "message").linesIterator.nextOption().getOrElse(""),
      author = opt(c.downField("author"), "login").orElse(opt(c.downField("commit").downField("author"), "name")),
      sha = opt(c, "sha"), updatedAt = opt(c.downField("commit").downField("author"), "date"), url = str(c, "html_url"))
    case SearchKind.issues | SearchKind.prs =>
      val repoName = opt(c, "repository_url").flatMap { u => val p = u.split("/"); Option.when(p.length >= 2)(s"${p(p.length - 2)}/${p.last}") }
      SearchItem(if c.downField("pull_request").focus.nonEmpty then "pr" else "issue", repoName, c.get[Int]("number").toOption,
        str(c, "title"), opt(c, "state"), opt(c.downField("user"), "login"), updatedAt = opt(c, "updated_at"), url = str(c, "html_url"))

  private val keptHeaderNames = Set("etag", "link", "location", "x-ratelimit-limit", "x-ratelimit-remaining", "x-ratelimit-reset", "x-oauth-scopes")
  private def keepHeaders(h: Map[String, String]): Map[String, String] = h.filter((k, _) => keptHeaderNames(k))
  private def tail(text: String, maxLines: Int): String =
    val ls = text.linesIterator.toVector
    if ls.size <= maxLines then ls.mkString("\n") else s"... (${ls.size - maxLines} earlier lines omitted) ...\n" + ls.takeRight(maxLines).mkString("\n")
  private def decodeUtf8(b: Array[Byte]): Option[String] =
    try Some(UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString)
    catch case _: CharacterCodingException => None
  private def enc(s: String): String = URLEncoder.encode(s, UTF_8).replace("+", "%20")
  private def clamp(v: Int, min: Int, max: Int): Int = math.max(min, math.min(max, v))
  private def cleanOpt(v: Option[String]): Option[String] = v.map(_.trim).filter(_.nonEmpty)
  private def optParam(k: String, v: Option[String]): Map[String, String] = cleanOpt(v).map(k -> _).toMap
  private def jsonObj(fields: (String, Option[Json])*): Json = Json.fromFields(fields.collect { case (k, Some(v)) => k -> v })
  private def labelNames(c: ACursor): Vector[String] = arr(c.downField("labels").focus.getOrElse(Json.arr())).flatMap(_.hcursor.get[String]("name").toOption)
  private def str(c: ACursor, k: String, fallback: String = ""): String = c.get[Option[String]](k).toOption.flatten.getOrElse(fallback)
  private def opt(c: ACursor, k: String): Option[String] = c.get[Option[String]](k).toOption.flatten.filter(_.nonEmpty)
  private def int(c: ACursor, k: String): Int = c.get[Int](k).getOrElse(0)
  private def long(c: ACursor, k: String): Long = c.get[Long](k).getOrElse(0L)
  private def bool(c: ACursor, k: String): Boolean = c.get[Boolean](k).getOrElse(false)
  private def arr(j: Json): Vector[Json] = j.asArray.getOrElse(Vector.empty)
