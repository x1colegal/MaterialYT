#!/bin/bash
curl -s -X POST "https://www.youtube.com/youtubei/v1/player?prettyPrint=false" \
-H "User-Agent: com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 18_7_2 like Mac OS X; en_US)" \
-H "Content-Type: application/json" \
-H "X-Youtube-Client-Name: 5" \
-H "X-Youtube-Client-Version: 19.29.1" \
-d '{
  "context": {
    "client": {
      "clientName": "IOS",
      "clientVersion": "19.29.1",
      "platform": "MOBILE",
      "osName": "iOS",
      "osVersion": "18.7.2",
      "deviceModel": "iPhone16,2",
      "hl": "en",
      "gl": "US"
    }
  },
  "videoId": "jNQXAC9IVRw",
  "cpn": "1234567890123456"
}' | jq '.playabilityStatus.status'
