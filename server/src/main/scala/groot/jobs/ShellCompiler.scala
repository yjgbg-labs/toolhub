package groot.jobs

/** The client runs only /bin/sh and standard Linux utilities, with no YAML parser or language runtime. */
object ShellCompiler:
  private def quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

  def compile(jobs: Vector[Job], parallel: Int): String =
    require(parallel > 0)
    val ids = jobs.zipWithIndex.map((job, id) => job.name -> id).toMap
    val script = new StringBuilder("""#!/bin/sh
set -u
work=$(mktemp -d "${TMPDIR:-/tmp}/groot-jobs.XXXXXXXX") || exit 1
cleanup() {
  trap - EXIT HUP INT TERM
""")
    jobs.indices.foreach { id =>
      script.append(s"""  if [ -n "$${pid_$id:-}" ]; then kill "$$pid_$id" 2>/dev/null || :; fi
""")
    }
    jobs.indices.foreach { id =>
      script.append(s"""  if [ -n "$${pid_$id:-}" ]; then wait "$$pid_$id" 2>/dev/null || :; fi
""")
    }
    script.append("""  rm -rf "$work"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
mkfifo "$work/events" || exit 1
# Keep both FIFO ends open so concurrent completion messages cannot be lost
# between reads. Each worker writes one short, atomic message.
exec 3<&0 4<>"$work/events"
start_job() {
  (
    child=
    trap 'if [ -n "$child" ]; then kill "$child" 2>/dev/null || :; wait "$child" 2>/dev/null || :; fi; exit 143' TERM
    /bin/sh -e -c "$2" <&3 3<&- 4>&- &
    child=$!
    wait "$child"
    status=$?
    printf '%s %s\n' "$1" "$status" >&4
  ) &
}
running=0
failed=0
""")
    script.append(s"remaining=${jobs.size}\n")
    jobs.indices.foreach(id => script.append(s"state_$id=pending\npid_$id=\n"))
    script.append("while [ \"$remaining\" -gt 0 ]; do\n")
    jobs.zipWithIndex.foreach { (job, id) =>
      val dependencies = job.needs.map(ids)
      val blocked = dependencies.map(dep => s"[ \"$$state_$dep\" = failed ] || [ \"$$state_$dep\" = skipped ]").mkString(" || ")
      val ready = (Vector(s"[ \"$$running\" -lt $parallel ]") ++ dependencies.map(dep => s"[ \"$$state_$dep\" = succeeded ]")).mkString(" && ")
      script.append(s"  if [ \"$$state_$id\" = pending ]; then\n")
      if dependencies.nonEmpty then
        script.append(s"""    if $blocked; then
      state_$id=skipped
      remaining=$$((remaining - 1))
      failed=1
      printf '[jobs] skip  %s (dependency failed)\\n' ${quote(job.name)} >&2
    elif $ready; then
""")
      else script.append(s"    if $ready; then\n")
      script.append(s"""      state_$id=running
      running=$$((running + 1))
      started_$id=$$(date +%s%3N)
      printf '[jobs] start %s\\n' ${quote(job.name)}
      start_job $id ${quote(job.run)}
      pid_$id=$$!
    fi
  fi
""")
    }
    script.append("""  [ "$remaining" -gt 0 ] || break
  [ "$running" -gt 0 ] || exit 1
  IFS=' ' read -r completed status <&4 || exit 1
  running=$((running - 1))
  remaining=$((remaining - 1))
  case "$completed" in
""")
    jobs.zipWithIndex.foreach { (job, id) =>
      script.append(s"""    $id)
      wait "$$pid_$id" || :
      pid_$id=
      duration=$$(( $$(date +%s%3N) - started_$id ))
      if [ "$$status" -eq 0 ]; then
        state_$id=succeeded
        printf '[jobs] done  %s (%sms)\\n' ${quote(job.name)} "$$duration"
      else
        state_$id=failed
        failed=1
        printf '[jobs] fail  %s (exit %s, %sms)\\n' ${quote(job.name)} "$$status" "$$duration" >&2
      fi
      ;;
""")
    }
    script.append("""    *) exit 1 ;;
  esac
done
exit "$failed"
""").result()
