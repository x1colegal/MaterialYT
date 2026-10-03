#!/bin/bash
curl -s -X POST "https://www.youtube.com/youtubei/v1/player?prettyPrint=false" \
-H "Content-Type: application/json" \
-H "User-Agent: com.google.android.youtube/20.05.35 (Linux; U; Android 14)" \
-H "X-Youtube-Client-Name: 3" \
-H "X-Youtube-Client-Version: 20.05.35" \
-d '{
  "context": {
    "client": {
      "clientName": "ANDROID",
      "clientVersion": "20.05.35",
      "platform": "MOBILE",
      "osName": "Android",
      "osVersion": "14",
      "androidSdkVersion": 34,
      "deviceModel": "Pixel 8 Pro"
    }
  },
  "videoId": "jNQXAC9IVRw"
}' | jq '.playabilityStatus.status'
