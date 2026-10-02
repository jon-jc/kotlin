SELECT s.name, ROUND(MAX(s.dur)/1000000.0,2) AS max_ms, COUNT(*) AS count
FROM slice s JOIN thread_track tt ON s.track_id=tt.id
JOIN thread t ON tt.utid=t.utid JOIN process p ON t.upid=p.upid
WHERE p.name='com.roam.app' AND t.tid=p.pid AND s.dur>0
GROUP BY s.name ORDER BY max_ms DESC LIMIT 35;
