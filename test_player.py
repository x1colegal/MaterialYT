import urllib.request
import json

videoId = "dQw4w9WgXcQ"
cpn = "1234567890123456"
name = "IOS"
version = "19.33.2"
id = "5"
model = "iPhone14,3"
os = "iOS"
osVersion = "16.2"
ua = f"com.google.ios.youtube/{version} (iPhone; U; CPU iOS 18_7_2 like Mac OS X)"

clientContext = {
    "clientName": name,
    "clientVersion": version,
    "deviceModel": model,
    "osName": os,
    "osVersion": osVersion,
    "platform": "MOBILE",
    "clientScreen": "WATCH",
    "hl": "en",
    "gl": "US"
}
body = {
    "context": {"client": clientContext},
    "videoId": videoId,
    "contentCheckOk": True,
    "racyCheckOk": True,
    "cpn": cpn
}
data = json.dumps(body).encode('utf-8')
req = urllib.request.Request("https://www.youtube.com/youtubei/v1/player?prettyPrint=false&id=dQw4w9WgXcQ", data=data, headers={
    "User-Agent": ua,
    "Content-Type": "application/json",
    "X-Youtube-Client-Name": id,
    "X-Youtube-Client-Version": version
})

try:
    res = urllib.request.urlopen(req)
    res_data = res.read()
    j = json.loads(res_data)
    print(json.dumps(j.get("playbackTracking", {}), indent=2))
except Exception as e:
    print(e)
