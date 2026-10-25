"""Private per-account live snapshots. No video is sent to a model here."""
import base64
import os
import re
import secrets
import time
from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import Response
from auth import current_user, limit as rate
from database import store, digest

router=APIRouter()

def enabled():
    return os.getenv('CAMERA_ENABLED','false').lower()=='true'

def key_for(user_id,create=False,rotate=False):
    service=store()
    with service.connect() as db:
        if create:db.execute('BEGIN IMMEDIATE')
        row=db.execute('SELECT * FROM network_cameras WHERE user_id=?',(user_id,)).fetchone()
        if create and (row is None or rotate):
            key=secrets.token_hex(16)
            db.execute('INSERT INTO network_cameras VALUES (?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET key_hash=excluded.key_hash,encrypted_key=excluded.encrypted_key,updated_at=excluded.updated_at',
                       (user_id,digest(key),service.encrypt(key),int(time.time())))
            if rotate:db.execute("INSERT INTO audit(actor_id,target_id,action,created_at) VALUES (?,?,'camera_key_rotated',?)",(user_id,user_id,int(time.time())))
            return key
        return service.cipher.decrypt(row['encrypted_key'].encode()).decode() if row else None

def configuration(user_id,rotate=False):
    if not enabled():raise HTTPException(503,'本机接流服务未启用，请运行 setup-camera.cmd')
    key=key_for(user_id,True,rotate)
    host=os.getenv('CAMERA_RTMP_HOST','').strip()
    # Configuration values are never accepted as arbitrary upstream fetch URLs.
    if not re.fullmatch(r'[A-Za-z0-9.-]+',host):raise HTTPException(503,'请先在本机接流设置中配置电脑局域网 IP')
    port=int(os.getenv('CAMERA_RTMP_PORT','1935'))
    return {'enabled':True,'server_url':f'rtmp://{host}:{port}/live',
            'stream_key':'cam_'+key,'stream_path':'live/cam_'+key,'publish_url':f'rtmp://{host}:{port}/live/cam_{key}',
            'frame_path':'/v1/network-camera/frame','preview_fps':3,'max_frame_age_ms':2500,
            'note':'使用 DJI Mimo 自定义 RTMP，1080p/6Mbps；推流密钥只供本人使用。'}

@router.post('/v1/network-camera/config')
async def camera_config(user=Depends(current_user)):
    rate('camera-config:'+str(user['id']),30,60)
    return configuration(user['id'])

@router.post('/v1/network-camera/rotate')
async def camera_rotate(user=Depends(current_user)):
    rate('camera-rotate:'+str(user['id']),5,60)
    return configuration(user['id'],True)

@router.post('/internal/camera-auth')
async def camera_auth(request:Request):
    expected=os.getenv('CAMERA_AUTH_SECRET','')
    supplied=request.headers.get('authorization','')
    try:
        plain=base64.b64decode(supplied[6:],validate=True).decode() if supplied.startswith('Basic ') else ''
    except Exception:plain=''
    if not enabled() or len(expected)<32 or not secrets.compare_digest(plain,'cameraauth:'+expected):
        raise HTTPException(403,'Forbidden')
    try:data=await request.json()
    except Exception:raise HTTPException(403,'Forbidden')
    if not isinstance(data,dict):raise HTTPException(403,'Forbidden')
    path=data.get('path','')
    if not isinstance(path,str) or not re.fullmatch(r'live/cam_[a-f0-9]{32}',path):raise HTTPException(403,'Forbidden')
    with store().connect() as db:
        user=db.execute('SELECT u.status,u.must_change_password FROM network_cameras c JOIN users u ON u.id=c.user_id WHERE c.key_hash=?',(digest(path[9:]),)).fetchone()
    if not user or user['status']!='active' or user['must_change_password']:raise HTTPException(403,'Forbidden')
    allowed=data.get('action')=='publish' and data.get('protocol')=='rtmp'
    allowed=allowed or data.get('action')=='read' and data.get('protocol')=='rtsp' and data.get('ip') in ('127.0.0.1','::1')
    if not allowed:raise HTTPException(403,'Forbidden')
    return Response(status_code=204)

@router.get('/v1/network-camera/frame')
async def camera_frame(user=Depends(current_user)):
    if not enabled():raise HTTPException(503,'本机接流服务未启用')
    rate('camera-frame:'+str(user['id']),480,60)
    key=key_for(user['id'])
    if not key:raise HTTPException(409,'请先打开网络摄像头接流设置，获取个人推流地址')
    file=Path(os.getenv('CAMERA_FRAME_DIR','/camera-frames'))/('cam_'+key+'.jpg')
    try:
        # Open once so atomic FFmpeg replacement cannot mix bytes and timestamp.
        with file.open('rb') as stream:
            stat=os.fstat(stream.fileno())
            age=time.time()-stat.st_mtime
            if age>2.5 or age< -1:raise HTTPException(503,'相机已断流或画面过期，请检查 DJI Mimo')
            if stat.st_size>2*1024*1024:raise HTTPException(503,'相机帧过大')
            image=stream.read(2*1024*1024+1)
    except FileNotFoundError:raise HTTPException(503,'等待 Action 4 推流，请先在 DJI Mimo 中开始直播')
    if not image.startswith(b'\xff\xd8') or len(image)>2*1024*1024:raise HTTPException(503,'相机画面不可用')
    return Response(image,media_type='image/jpeg',headers={
        'Cache-Control':'no-store','X-Camera-Frame':str(stat.st_mtime_ns),
        'X-Camera-Age-Ms':str(max(0,int(age*1000)))})
