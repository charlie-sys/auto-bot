"""Per-account and device cancellation. Holds IDs/tasks, never images or credentials."""
import asyncio
import re
import time
from fastapi import HTTPException

active={}
recent={}

class Operation:
    def __init__(self,key=None,channel=None):
        self.key=key;self.channel=channel;self.task=None;self.cancelled=False;self.done=asyncio.Event()
    def activate(self):
        if self.cancelled:raise HTTPException(499,'请求已取消')
        self.task=asyncio.current_task()
    def cancel(self):
        if self.cancelled:return
        self.cancelled=True
        if self.task and not self.done.is_set():self.task.cancel()
    def close(self):
        if self.key and active.get(self.key) is self:
            active.pop(self.key,None);recent[self.key]=time.monotonic()
        self.done.set()

async def begin(request,user):
    identifier=request.headers.get('x-solve-id','') if request else ''
    channel=request.headers.get('x-solve-channel','') if request else ''
    if not identifier:return Operation()
    if not re.fullmatch('[a-f0-9]{32}',identifier) or channel and not re.fullmatch('[a-f0-9]{32}',channel):raise HTTPException(400,'识题请求标识无效')
    now=time.monotonic()
    for key,created in list(recent.items()):
        if now-created>120:recent.pop(key,None)
    if len(recent)>2048:
        for key in list(recent)[:len(recent)-2048]:recent.pop(key,None)
    key=(user['id'],identifier)
    if key in recent:raise HTTPException(409,'该请求已取消或完成，请重新拍摄')
    if key in active:raise HTTPException(409,'请勿重复提交同一识题请求')
    if len(active)>=256:raise HTTPException(429,'识题服务繁忙，请稍后重试')
    if channel:
        while True:
            if key in recent or key in active:raise HTTPException(409,'请勿重复提交同一识题请求')
            previous=[op for op in list(active.values()) if op.key[0]==user['id'] and op.channel==channel]
            if not previous:break
            for op in previous:op.cancel()
            for op in previous:
                try:await asyncio.wait_for(op.done.wait(),timeout=1)
                except asyncio.TimeoutError:raise HTTPException(409,'旧请求正在停止，请稍后重新拍摄')
    operation=Operation(key,channel);active[key]=operation;return operation

def cancel(user_id,identifier):
    key=(user_id,identifier);operation=active.get(key)
    if operation:operation.cancel()
    else:
        if len(recent)>=4096:recent.pop(next(iter(recent)),None)
        recent[key]=time.monotonic()
