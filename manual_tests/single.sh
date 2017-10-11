#!/bin/bash
curl -X GET -H "content-type: application/json" "http://localhost:8080/search?tag=scala&tag=clojure"
