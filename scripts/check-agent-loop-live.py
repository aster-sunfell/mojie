#!/usr/bin/env python3
"""Run the five PRD live-model cases through an already started, isolated Java API.

Only Python's standard library is needed. Creates new test projects, applies one
test proposal twice, and keeps all fixtures and failed runs for investigation.
Model credentials stay in the Java process; this script never reads them.
"""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request
import uuid


def fixtures():
    short = "林舟站在河边，收好地图。\n林舟游到了对岸。\n他回头望了望山路，继续向城镇走去。"
    paragraphs = [
        f"第{i:02d}处路标旁，林舟停下脚步，核对地图上用墨线标出的方向。山风吹过树梢，远处的石桥清晰可见。"
        "他记下沿途的石墙、松林与村舍，准备日后整理成一份旅途笔记。"
        for i in range(1, 90)
    ]
    ending = "\n\n林舟游到了对岸。\n他收好地图，沿着石阶走向城镇。"
    long = "\n\n".join(paragraphs)[:5900 - len(ending)] + ending
    return [
        dict(id="readonly", name="无需修改", target="林舟望着深水，决定不上水。他沿石桥走到了对岸。",
             instruction="请结合前文和人物设定，检查目标章中林舟的行为是否矛盾。只做分析，不提交修改建议；说明实际查阅范围。"),
        dict(id="edit", name="矛盾与采纳", target=short, adopt=True,
             expected=short.replace("林舟游到了对岸。", "林舟沿桥走到了对岸。"),
             instruction="检查林舟是否与前文、人物设定矛盾。请分别搜索章节和设定里的“游泳”，读取完整证据；"
                         "如有矛盾，只将“林舟游到了对岸。”替换为“林舟沿桥走到了对岸。”，其他文字原样保留，并提交完整目标章候选稿。"),
        dict(id="long", name="5900码点完整候选", target=long,
             expected=long.replace("林舟游到了对岸。", "林舟沿桥走到了对岸。"),
             instruction="核对人物行为与前文、设定是否矛盾。若发现不会游泳却下水的矛盾，只把目标正文中的“林舟游到了对岸。”"
                         "替换为“林舟沿桥走到了对岸。”，其余全部段落逐字保留，提交完整章节候选稿，不能省略、概括或使用占位符。"),
        dict(id="synonym", name="字面无命中有同义证据", target="林舟纵身跃入深水，独自泅渡到了对岸。他此前没有学过新本领。",
             previous="林舟从未学会泅水，过去总靠渡船过河。", setting="林舟不谙水性，遇到深河必须寻桥或乘船。",
             instruction="先在前文章节中搜索“游泳”。无命中时请继续查阅前文与设定，判断目标章行为是否矛盾，"
                         "并说明字面搜索的局限。只分析，不提交修改建议。"),
        dict(id="insufficient", name="信息不足", target="林舟收到一封没有署名的信。信上只写着：今晚到旧桥相见。",
             instruction="检查是否有充分证据证明匿名信的寄信人是林舟失散多年的哥哥。请查看前文和设定，只分析，"
                         "不提交候选稿，明确区分推测和文本证据。"),
    ]


class Audit:
    def __init__(self, args):
        self.base = args.base_url.rstrip("/")
        self.output = args.output
        commit = subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
        self.report = dict(startedAt=self.now(), gitCommit=commit, api=self.base,
                           expectedModel=args.model, cases=[],
                           scope="Technical live-model assertions; final literary judgments require human review.")

    @staticmethod
    def now():
        return datetime.now(timezone.utc).isoformat()

    def save(self):
        temporary = self.output.with_suffix(self.output.suffix + ".tmp")
        temporary.write_text(json.dumps(self.report, ensure_ascii=False, indent=2) + "\n")
        temporary.replace(self.output)

    def api(self, path, method="GET", body=None):
        request = urllib.request.Request(self.base + path, method=method,
                  data=None if body is None else json.dumps(body, ensure_ascii=False).encode(),
                  headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                return json.load(response)["data"]
        except urllib.error.HTTPError as error:
            # Product errors are sanitized by the API; never call the provider here.
            raise RuntimeError(f"{method} {path}: HTTP {error.code} {error.read().decode()[:500]}") from error

    def run_case(self, fixture):
        entry = dict(caseId=fixture["id"], name=fixture["name"], startedAt=self.now(),
                     inputCodePoints=len(fixture["target"]), instruction=fixture["instruction"])
        self.report["cases"].append(entry)
        self.save()
        print("START " + fixture["id"], flush=True)
        project = self.api("/projects", "POST", dict(title="Agent Loop 实测 " + fixture["name"]))
        entry["projectId"] = project["id"]
        texts = [
            ("第一章·旧事", fixture.get("previous", "林舟明确说自己不会游泳，遇到深水只能绕行或走桥。")),
            ("第二章·出发", "林舟查看地图，发现河上有一座安全的石桥。他尚未学习新技能。"),
            ("第三章·目标", fixture["target"]),
        ]
        chapters = []
        for title, content in texts:
            chapter = self.api(f"/projects/{project['id']}/chapters", "POST", dict(title=title))
            chapter = self.api(f"/chapters/{chapter['id']}/content", "PUT", dict(content=content, expectedVersion=1))
            chapters.append(chapter)
        for kind, title, content in [
            ("CHARACTER", "林舟", fixture.get("setting", "林舟不会游泳，熟悉步行路线。")),
            ("WORLD", "河与桥", "河水很深，石桥完整，可以安全通行。"),
            ("RULE", "能力变化", "人物能力的变化需要交代合理原因。"),
        ]:
            self.api(f"/projects/{project['id']}/settings", "POST", dict(type=kind, title=title, content=content))
        target = chapters[-1]
        entry["targetChapterId"] = target["id"]
        receipt = self.api("/agent/runs", "POST", dict(projectId=project["id"], targetChapterId=target["id"],
                           expectedVersion=target["version"], instruction=fixture["instruction"], clientRequestId=str(uuid.uuid4())))
        run_id = receipt["runId"]
        entry["runId"] = run_id
        self.save()
        started = time.monotonic()
        while True:
            run = self.api("/agent/runs/" + run_id)
            if run["status"] in {"SUCCEEDED", "FAILED", "CANCELLED"}:
                break
            if time.monotonic() - started > 250:
                raise RuntimeError("Observation limit exceeded; inspect the recorded runId before retrying")
            time.sleep(1)
        entry.update(elapsedSeconds=round(time.monotonic() - started, 1), run=run)
        self.save()
        current = self.api("/chapters/" + target["id"])
        checks = dict(succeeded=run["status"] == "SUCCEEDED",
                      chapterUnchangedBeforeAdoption=current["content"] == fixture["target"] and current["version"] == target["version"],
                      targetFullReadProvided=any(s["kind"] == "FULL_CHAPTER" and s.get("chapterId") == target["id"]
                                                and s["providedToModel"] for s in run["sources"]),
                      expectedProposalPresence=bool(run.get("proposalId")) == ("expected" in fixture))
        trace = self.api("/agent/runs/" + run_id + "/trace")
        entry["modelCalls"] = [{k: call.get(k) for k in
                               ["callIndex", "status", "finishReason", "rawFinishReason", "usage", "errorCode", "errorDetail"]}
                              for call in trace["modelCalls"]]
        if run.get("proposalId"):
            proposal = self.api("/edit-proposals/" + run["proposalId"])
            entry["proposal"] = proposal
            entry["proposalCodePoints"] = len(proposal["proposedContent"])
            checks["proposalPending"] = proposal["status"] == "PENDING"
            checks["proposalMatchesExpectedExactly"] = proposal["proposedContent"] == fixture.get("expected")
            if fixture.get("adopt"):
                first = self.api("/edit-proposals/" + proposal["id"] + "/accept", "POST")
                repeat = self.api("/edit-proposals/" + proposal["id"] + "/accept", "POST")
                latest = self.api("/chapters/" + target["id"])
                history = self.api("/chapters/" + target["id"] + "/revisions")["items"]
                entry["adoption"] = dict(first=first, repeat=repeat, currentVersion=latest["version"], revisions=history)
                checks["adoptedExactlyOnce"] = (first["status"] == repeat["status"] == "APPLIED"
                         and latest["version"] == target["version"] + 1
                         and latest["content"] == proposal["proposedContent"]
                         and sum(r["source"] == "AGENT" for r in history) == 1)
        with urllib.request.urlopen(urllib.request.Request(self.base + "/agent/runs/" + run_id + "/events",
                                     headers={"Last-Event-ID": "2"}), timeout=15) as response:
            events = [json.loads(line[5:]) for line in response.read().decode().splitlines() if line.startswith("data:")]
        entry["sseReplay"] = dict(count=len(events), lastType=events[-1]["type"] if events else None)
        checks["sseContinuousToTerminal"] = bool(events) and all(e["seq"] == 3 + i for i, e in enumerate(events)) \
            and events[-1]["type"] == "run." + run["status"].lower()
        entry["checks"] = checks
        entry["passed"] = all(checks.values())
        self.save()
        print(json.dumps(dict(caseId=fixture["id"], runId=run_id, status=run["status"], passed=entry["passed"],
                              elapsedSeconds=entry["elapsedSeconds"], modelCalls=run["modelCallCount"],
                              toolCalls=run["toolCallCount"], errorCode=run.get("errorCode"), checks=checks), ensure_ascii=False), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True, help="Isolated Java API, including /api/v1; enable MOJIE_DEV_TRACE=true")
    parser.add_argument("--output", required=True, type=Path, help="New JSON evidence file; existing files are never overwritten")
    parser.add_argument("--model", required=True, help="Expected model for the record; verify against the run's DB configuration")
    parser.add_argument("--cases", nargs="+", choices=[f["id"] for f in fixtures()], help="Run selected cases only")
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Output already exists; choose a new filename to preserve previous attempts")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    audit = Audit(args)
    if audit.api("/health")["status"] != "UP":
        parser.error("Backend is not healthy")
    if not audit.api("/system/capabilities")["agentConfigured"]:
        parser.error("Backend has no configured model")
    selected = [f for f in fixtures() if not args.cases or f["id"] in args.cases]
    for fixture in selected:
        try:
            audit.run_case(fixture)
        except Exception as error:
            audit.report["harnessError"] = str(error)
            audit.save()
            raise
    audit.report["finishedAt"] = audit.now()
    audit.report["passed"] = all(case["passed"] for case in audit.report["cases"])
    audit.save()
    print("RESULTS " + str(args.output), flush=True)
    return 0 if audit.report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
