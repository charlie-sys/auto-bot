import base64
import os
import time
import unittest
from pathlib import Path
from unittest.mock import patch
import test_accounts
from network_camera import key_for
from tunnel_gateway import ALLOWED

class NetworkCameraTests(unittest.IsolatedAsyncioTestCase):
    make_user=test_accounts.AccountTests.make_user
    async def asyncSetUp(self):
        await test_accounts.AccountTests.asyncSetUp(self)
        self.frames=Path(self.temp.name)/'frames';self.frames.mkdir()
        self.camera_env=patch.dict(os.environ,{'CAMERA_ENABLED':'true','CAMERA_AUTH_SECRET':'a'*64,'CAMERA_RTMP_HOST':'192.168.1.23','CAMERA_FRAME_DIR':str(self.frames)})
        self.camera_env.start()
        self.internal={'Authorization':'Basic '+base64.b64encode(('cameraauth:'+'a'*64).encode()).decode()}
    async def asyncTearDown(self):
        self.camera_env.stop();await test_accounts.AccountTests.asyncTearDown(self)
    async def config(self,user=None):
        response=await self.client.post('/v1/network-camera/config',headers=(user or self.first)['headers'])
        self.assertEqual(response.status_code,200);return response.json()
    def frame(self,user=None):
        path=self.frames/('cam_'+key_for((user or self.first)['id'])+'.jpg')
        path.write_bytes(b'\xff\xd8\xff\xe0test');return path
    async def test_configuration_is_encrypted_and_owned(self):
        first=await self.config();second=await self.config(self.second)
        self.assertNotEqual(first['stream_key'],second['stream_key'])
        self.assertEqual((await self.config())['stream_key'],first['stream_key'])
        self.assertEqual(first['server_url'],'rtmp://192.168.1.23:1935/live')
        with self.db.connect() as db:
            row=db.execute('SELECT * FROM network_cameras WHERE user_id=?',(self.first['id'],)).fetchone()
            self.assertNotIn(first['stream_key'][4:],str(dict(row)))
    async def test_frames_require_auth_and_cannot_cross_accounts(self):
        await self.config();await self.config(self.second);self.frame()
        self.assertEqual((await self.client.get('/v1/network-camera/frame')).status_code,401)
        self.assertEqual((await self.client.get('/v1/network-camera/frame',headers=self.second['headers'])).status_code,503)
        response=await self.client.get('/v1/network-camera/frame',headers=self.first['headers'])
        self.assertEqual(response.status_code,200);self.assertEqual(response.headers['content-type'],'image/jpeg')
        self.assertTrue(response.headers['x-camera-frame'].isdigit());self.assertEqual(response.headers['cache-control'],'no-store')
        with self.db.connect() as db:self.assertEqual(db.execute('SELECT count(*) FROM usage').fetchone()[0],0)
    async def test_stale_future_and_invalid_frames_are_rejected(self):
        await self.config();path=self.frame()
        for stamp in (time.time()-10,time.time()+60):
            os.utime(path,(stamp,stamp))
            self.assertEqual((await self.client.get('/v1/network-camera/frame',headers=self.first['headers'])).status_code,503)
        path.write_bytes(b'bad-data')
        self.assertEqual((await self.client.get('/v1/network-camera/frame',headers=self.first['headers'])).status_code,503)
        path.write_bytes(b'\xff\xd8'+b'x'*(2*1024*1024))
        self.assertEqual((await self.client.get('/v1/network-camera/frame',headers=self.first['headers'])).status_code,503)
    async def test_internal_auth_allows_only_owned_publish_and_loopback_extractor(self):
        config=await self.config()
        body={'action':'publish','protocol':'rtmp','path':config['stream_path'],'ip':'192.168.1.4'}
        self.assertEqual((await self.client.post('/internal/camera-auth',json=body)).status_code,403)
        self.assertEqual((await self.client.post('/internal/camera-auth',headers=self.internal,json=body)).status_code,204)
        for changes in ({'path':'cam_'+'b'*32},{'path':'../../data/encryption.key'},{'action':'read'},{'protocol':'rtsp'}):
            self.assertEqual((await self.client.post('/internal/camera-auth',headers=self.internal,json={**body,**changes})).status_code,403)
        reader={**body,'action':'read','protocol':'rtsp','ip':'127.0.0.1'}
        self.assertEqual((await self.client.post('/internal/camera-auth',headers=self.internal,json=reader)).status_code,204)
        with self.db.connect() as db:db.execute("UPDATE users SET status='disabled' WHERE id=?",(self.first['id'],))
        self.assertEqual((await self.client.post('/internal/camera-auth',headers=self.internal,json=body)).status_code,403)
    async def test_rotated_keys_revoke_previous_frame_and_reconnect(self):
        old=await self.config();self.frame()
        new=(await self.client.post('/v1/network-camera/rotate',headers=self.first['headers'])).json()
        self.assertNotEqual(old['stream_key'],new['stream_key'])
        self.assertEqual((await self.client.get('/v1/network-camera/frame',headers=self.first['headers'])).status_code,503)
        body={'action':'publish','protocol':'rtmp','path':old['stream_path'],'ip':'192.168.1.4'}
        self.assertEqual((await self.client.post('/internal/camera-auth',headers=self.internal,json=body)).status_code,403)
        self.assertEqual((await self.client.post('/internal/camera-auth',headers=self.internal,json={**body,'path':new['stream_path']})).status_code,204)
    async def test_disabled_bridge_and_public_proxy_boundaries(self):
        with patch.dict(os.environ,{'CAMERA_ENABLED':'false'}):
            self.assertEqual((await self.client.post('/v1/network-camera/config',headers=self.first['headers'])).status_code,503)
        self.assertIsNone(ALLOWED.fullmatch('/internal/camera-auth'))
        for path in ('/v1/network-camera/config','/v1/network-camera/frame','/v1/network-camera/rotate'):self.assertIsNotNone(ALLOWED.fullmatch(path))

if __name__=='__main__':unittest.main()
