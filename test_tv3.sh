#!/bin/bash
curl -s -X POST "https://www.youtube.com/youtubei/v1/player?prettyPrint=false" \
-H "Content-Type: application/json" \
-d '{
  "context": {
    "client": {
      "clientName": "TVHTML5",
      "clientVersion": "7.20230405.08.01",
      "platform": "TV",
      "osName": "Web",
      "deviceModel": "Smart TV"
    }
  },
  "videoId": "dQw4w9WgXcQ",
  "playbackContext": {
    "contentPlaybackContext": {
      "signatureTimestamp": 19999
    }
  }
}' | jq '.playabilityStatus.status'
