#!/bin/bash
curl -s -X POST "https://music.youtube.com/youtubei/v1/player?prettyPrint=false" \
-H "Content-Type: application/json" \
-d '{
  "context": {
    "client": {
      "clientName": "WEB_REMIX",
      "clientVersion": "1.20230405.01.00",
      "platform": "DESKTOP",
      "osName": "Windows",
      "deviceModel": ""
    }
  },
  "videoId": "jNQXAC9IVRw"
}' | jq '.playabilityStatus.status, .streamingData.formats[0].url'
