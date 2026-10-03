#!/bin/bash
curl -s -X POST "https://www.youtube.com/youtubei/v1/player?prettyPrint=false" \
-H "Content-Type: application/json" \
-d '{
  "context": {
    "client": {
      "clientName": "WEB_EMBEDDED",
      "clientVersion": "1.20240901.01.00",
      "platform": "DESKTOP",
      "osName": "Windows"
    }
  },
  "videoId": "jNQXAC9IVRw"
}' | jq '.playabilityStatus.status'
