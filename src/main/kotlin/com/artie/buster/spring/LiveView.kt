package com.artie.buster.spring

import com.artie.buster.core.MutationRecord
import com.artie.buster.core.Outcome
import com.artie.buster.core.Verdict

/**
 * The live view of a run, in htmx. GET /_buster serves [PAGE], a shell that asks
 * /_buster/report for HTML every second; the server renders those rows with [rows].
 * No client-side rendering, so the page holds no logic of its own.
 */
internal object LiveView {

    /** Everything a row shows comes from the run; escape it all. */
    private fun esc(text: Any?): String = text.toString()
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** The rows for one moment of the run, newest mutation first. */
    fun rows(seed: Long, paceMs: Long, mutations: List<MutationRecord>, verdicts: List<Verdict>): String {
        val bySeq = verdicts.groupBy { it.record.seq }
        val open = mutations.count { it.seq !in bySeq }
        val pace = if (paceMs > 0) " · pace $paceMs ms" else ""
        return """
            <p class="meta">
              <span>seed ${esc(seed)}$pace</span>
              <span>mutations <b>${mutations.size}</b></span>
              <span class="pass">pass <b>${verdicts.countOf<Outcome.Pass>()}</b></span>
              <span class="FAIL">fail <b>${verdicts.countOf<Outcome.Fail>()}</b></span>
              <span class="inconclusive">inconclusive <b>${verdicts.countOf<Outcome.Inconclusive>()}</b></span>
              <span class="open">open <b>$open</b></span>
            </p>
            <div class="wrap"><table>
              <thead><tr>${HEADINGS.joinToString("") { "<th>$it</th>" }}</tr></thead>
              <tbody>${mutations.asReversed().joinToString("") { row(it, bySeq[it.seq].orEmpty()) }}</tbody>
            </table></div>
        """.trimIndent()
    }

    private fun row(m: MutationRecord, verdicts: List<Verdict>): String {
        val cells = listOf(
            esc(m.seq), esc(m.clientId), "<code>${esc(m.method)} ${esc(m.path)}</code>", esc(m.plugin),
            esc(m.spec.joinToString(", ")), esc(m.detail), verdictCell(verdicts),
        )
        return """<tr data-plugin="${esc(m.plugin)}">${cells.joinToString("") { "<td>$it</td>" }}</tr>"""
    }

    private fun verdictCell(verdicts: List<Verdict>): String =
        if (verdicts.isEmpty()) {
            """<span class="open">open</span>"""
        } else {
            verdicts.joinToString("") { v ->
                val why = listOf(v.expectation, v.outcome.reason).filter { it.isNotBlank() }.joinToString(" — ")
                val label = esc(v.outcome.label)
                """<div class="verdict $label">$label</div><div class="reason">${esc(why)}</div>"""
            }
        }

    private inline fun <reified T : Outcome> List<Verdict>.countOf() = count { it.outcome is T }

    private val HEADINGS =listOf("#", "client", "request", "plugin", "spec", "what changed", "verdict")

    val PAGE = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Buster live</title>
<script src="https://unpkg.com/htmx.org@2.0.3/dist/htmx.min.js"
  integrity="sha384-0895/pl2MU10Hqc6jd4RvrthNlDiE9U1tWmX7WRESftEDRosgxNsQG/Ze9YMRzHq"
  crossorigin="anonymous"></script>
<style>
  :root { --bg:#fbfaf7; --fg:#1d1c1a; --muted:#6b675f; --line:#e4e0d8; --pass:#1f7a3a; --fail:#b3261e; --open:#8a6d00; --row:#f3f0ea; }
  @media (prefers-color-scheme: dark) {
    :root { --bg:#161513; --fg:#ece9e2; --muted:#9a958b; --line:#2e2c28; --pass:#5fc27e; --fail:#ff7a6e; --open:#e0c050; --row:#1f1e1b; }
  }
  body { margin:0; background:var(--bg); color:var(--fg); font:14px/1.45 ui-sans-serif, system-ui, sans-serif; }
  main { max-width:1200px; margin:0 auto; padding:16px; }
  header { display:flex; flex-wrap:wrap; gap:8px 16px; align-items:baseline; }
  h1 { font-size:20px; margin:0; }
  .meta { color:var(--muted); display:flex; flex-wrap:wrap; gap:6px 16px; margin:8px 0 12px; }
  .meta b { font-variant-numeric:tabular-nums; }
  .pass { color:var(--pass); } .FAIL { color:var(--fail); font-weight:600; } .open { color:var(--open); }
  .inconclusive, .pending, .reason { color:var(--muted); }
  button { font:inherit; padding:4px 10px; border:1px solid var(--line); background:var(--row); color:var(--fg); border-radius:6px; cursor:pointer; }
  .wrap { overflow-x:auto; }
  table { border-collapse:collapse; width:100%; min-width:760px; }
  th, td { text-align:left; padding:6px 8px; border-bottom:1px solid var(--line); vertical-align:top; }
  th { color:var(--muted); font-weight:500; }
  code { font:12px ui-monospace, SFMono-Regular, Menlo, monospace; }
  .verdict { white-space:nowrap; } .reason { font-size:12px; }
</style>
</head>
<body>
<main>
  <header>
    <h1>Buster live</h1>
    <button hx-get="/_buster/report?flush=true" hx-target="#report" title="Close every open expectation now">End run</button>
    <a href="/_buster/report">raw JSON</a>
  </header>
  <div id="report" hx-get="/_buster/report" hx-trigger="load, every 1s">connecting…</div>
</main>
</body>
</html>
""".trimIndent()
}
