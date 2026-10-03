#!/bin/bash
curl -s -X POST "https://www.youtube.com/youtubei/v1/player?prettyPrint=false" \
-H "Content-Type: application/json" \
-d '{
  "context": {
    "client": {
      "clientName": "WEB_CREATOR",
      "clientVersion": "1.20240901.00.00"
    }
  },
  "videoId": "jNQXAC9IVRw"
}' | jq '.playabilityStatus.status'
