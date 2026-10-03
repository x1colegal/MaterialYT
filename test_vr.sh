#!/bin/bash
curl -s -X POST "https://www.youtube.com/youtubei/v1/player?prettyPrint=false" \
-H "Content-Type: application/json" \
-d '{
  "context": {
    "client": {
      "clientName": "ANDROID_VR",
      "clientVersion": "1.52.0",
      "platform": "MOBILE",
      "osName": "Android"
    }
  },
  "videoId": "jNQXAC9IVRw"
}' | jq '.playabilityStatus.status, .streamingData.formats[0].url'
