#!/usr/bin/env python3
"""Run conversation/Skill acceptance against an already configured isolated API. No credentials are read."""
import argparse
import io
import json
from pathlib import Path
import time
import urllib.request
import uuid
import zipfile

p=argparse.ArgumentParser()
p.add_argument('--base-url', default='http://127.0.0.1:19080/api/v1')
p.add_argument('--output', type=Path, required=True)
a=p.parse_args()
report={'cases':[], 'scope':'Live technical acceptance; literary quality remains subject to author review.'}
def save(): a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
def api(path,method='GET',body=None):
    req=urllib.request.Request(a.base_url+path,method=method,data=None if body is None else json.dumps(body,ensure_ascii=False).encode(),headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(req,timeout=30) as response:return json.load(response)['data']
def turn(conversation,content,task='ASSISTANT',chapter=None,mentions=None):
    c=api('/conversations/'+conversation)
    body={'content':content,'clientRequestId':str(uuid.uuid4()),'expectedConversationSeq':c['lastSeq'],'taskType':task,'skillMentions':mentions or []}
    if chapter:body.update(targetChapterId=chapter['id'],expectedVersion=chapter['version'])
    receipt=api('/conversations/'+conversation+'/messages','POST',body)
    entry={'input':body,'receipt':receipt};report['cases'].append(entry);save();print('START',receipt['runId'],flush=True)
    end=time.monotonic()+330
    while time.monotonic()<end:
        run=api('/agent/runs/'+receipt['runId'])
        if run['status'] in ('SUCCEEDED','FAILED','CANCELLED'):break
        time.sleep(1)
    entry.update(run=run,resources=api('/agent/runs/'+receipt['runId']+'/resources'));save();print('FINISH',run['status'],run.get('errorCode'),flush=True)
    return entry

try:
    report['model']=api('/system/capabilities')['modelId']
    project=api('/projects','POST',{'title':'连续对话与完整 Skill 真实验收'})
    report['projectId']=project['id']
    chapter=api('/projects/'+project['id']+'/chapters','POST',{'title':'河边'})
    chapter=api('/chapters/'+chapter['id']+'/content','PUT',{'content':'林舟来到河边。他游到了对岸。','expectedVersion':chapter['version']})
    api('/projects/'+project['id']+'/settings','POST',{'type':'CHARACTER','title':'林舟','content':'林舟不会游泳，遇到深水只能走桥或乘船。他也怕火。'})
    buffer=io.BytesIO()
    with zipfile.ZipFile(buffer,'w') as z:
        z.writestr('SKILL.md','---\nname: saved-chapter-report\ndescription: 作者要求生成可下载的章节检查报告时，读取参考指南并执行 Python 报告脚本。\n---\n先阅读 references/report.md，查阅完整章节及人物设定。scripts/report.py 接收已经完整读取的章节作为 CHAPTER 类型输入 content.txt，生成可下载报告。包根 /skill、输入 /input、输出 /output。arguments 使用空数组。完成后给作者报告下载链接。不能声称已修改正式正文。\n')
        z.writestr('references/report.md','报告注明输入来源为已保存章节，保留章节原文及简单统计。文学一致性结论应另行依据设定解释，脚本统计不是文学判断。\n')
        z.writestr('scripts/report.py',"from pathlib import Path\ncontent=Path('/input/content.txt').read_text()\nPath('/output/report.txt').write_text('已保存章节检查报告\\n原文字数：'+str(len(content))+'\\n章节原文：\\n'+content)\nprint('report.txt generated')\n")
        z.writestr('assets/data.bin',bytes([0,255,1]))
    boundary='mojie-'+uuid.uuid4().hex
    raw=b'--'+boundary.encode()+b'\r\nContent-Disposition: form-data; name="files"; filename="report.zip"\r\nContent-Type: application/zip\r\n\r\n'+buffer.getvalue()+b'\r\n--'+boundary.encode()+b'--\r\n'
    req=urllib.request.Request(a.base_url+'/skills/import',data=raw,headers={'Content-Type':'multipart/form-data; boundary='+boundary})
    with urllib.request.urlopen(req,timeout=30) as response:upload=json.load(response)['data']
    assert upload['status']=='READY',upload
    skill=api('/skills/'+upload['skillId'])['skill'];api('/skills/'+skill['id'],'PATCH',{'expectedVersion':skill['version'],'enabled':True})
    report['skillId']=skill['id'];report['versionId']=upload['versionId']
    conversation=api('/projects/'+project['id']+'/conversations','POST')['id'];report['conversationId']=conversation
    turn(conversation,'请记住，林舟怕火。这轮只记住这一点，不用生成报告。')
    turn(conversation,'结合上一轮的特点，用一句话说明他见到火把会如何反应。这轮不用生成报告。')
    result=turn(conversation,'请为已保存章节生成并交付可下载的检查报告。章节 ID 为 '+chapter['id']+'。请发现适用的 Skill，读取正文和参考指南，读取章节与人物设定，并执行包内 Python 脚本。必须使用 CHAPTER 来源输入而非重写章节文本。')
    downloads=[]
    for artifact in result['resources']['artifacts']:
        with urllib.request.urlopen(a.base_url+'/artifacts/'+artifact['id']+'/download') as response:content=response.read().decode()
        downloads.append({'artifactId':artifact['id'],'content':content})
    result['downloads']=downloads
    edit=turn(conversation,'读取本轮完整目标章及人物设定，林舟不会游泳。只把“他游到了对岸。”改成“他沿桥走到了对岸。”，其余原样保留，提交完整候选稿。不要运行报告脚本。','CHAPTER_EDIT',chapter)
    if edit['run'].get('proposalId'):
        proposal=api('/edit-proposals/'+edit['run']['proposalId'])
        edit['proposal']=proposal
        accepted=api('/edit-proposals/'+edit['run']['proposalId']+'/accept','POST',{'expectedVersion':chapter['version']})
        edit['acceptance']=accepted
        edit['savedChapter']=api('/chapters/'+chapter['id'])
    report['technicalAssertions']={'allSucceeded':all(e['run']['status']=='SUCCEEDED' for e in report['cases']),'thirdTurnLoadedSkill':any(s['loaded'] for s in result['resources']['skills']),'artifactDownloaded':bool(downloads),'editApplied':edit.get('savedChapter',{}).get('content')=='林舟来到河边。他沿桥走到了对岸。'}
    save()
except Exception as error:
    report['auditError']=str(error);save();raise
