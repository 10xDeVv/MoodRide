-- Flyway Migration V5: Insert sample Portland, Oregon road segments
-- This script populates ~50 realistic road segments covering Portland metro area
-- Coordinates centered around Portland (45.5 N, 122.7 W)
-- Schema: osm_way_id, geometry, h3_tile_index, length_meters, speed_limit_kmh, road_type, surface, curvature, elevation_change

INSERT INTO road_segments (osm_way_id, geometry, h3_tile_index, length_meters, speed_limit_kmh, road_type, surface, curvature, elevation_change) 
VALUES
-- Downtown Core - Urban Grid (Low curvature 0.1-0.3)
(10001, ST_GeomFromText('LINESTRING(-122.6789 45.5157, -122.6795 45.5165)', 4326), '891e83d373cffff', 150, 30, 'ARTERIAL', 'asphalt', 0.15, 2.5),
(10002, ST_GeomFromText('LINESTRING(-122.6795 45.5165, -122.6801 45.5172)', 4326), '891e83d373cffff', 180, 30, 'ARTERIAL', 'asphalt', 0.12, 1.8),
(10003, ST_GeomFromText('LINESTRING(-122.6801 45.5172, -122.6807 45.5180)', 4326), '891e83d373cffff', 160, 35, 'ARTERIAL', 'asphalt', 0.18, 2.1),
(10004, ST_GeomFromText('LINESTRING(-122.6807 45.5180, -122.6813 45.5187)', 4326), '891e83d373cffff', 170, 35, 'ARTERIAL', 'asphalt', 0.14, 1.9),
(10005, ST_GeomFromText('LINESTRING(-122.6813 45.5187, -122.6819 45.5195)', 4326), '891e83d373cffff', 175, 30, 'ARTERIAL', 'asphalt', 0.10, 2.2),

-- Morrison Bridge area connections (Moderate curvature 0.25-0.45)
(10006, ST_GeomFromText('LINESTRING(-122.6780 45.5200, -122.6765 45.5208)', 4326), '891e83d373cffff', 220, 40, 'ARTERIAL', 'asphalt', 0.35, 3.2),
(10007, ST_GeomFromText('LINESTRING(-122.6765 45.5208, -122.6750 45.5216)', 4326), '891e83d373cffff', 240, 40, 'ARTERIAL', 'asphalt', 0.38, 3.5),
(10008, ST_GeomFromText('LINESTRING(-122.6750 45.5216, -122.6735 45.5224)', 4326), '891e83d373cffff', 260, 45, 'ARTERIAL', 'asphalt', 0.40, 3.8),
(10009, ST_GeomFromText('LINESTRING(-122.6735 45.5224, -122.6720 45.5232)', 4326), '891e83d373cffff', 250, 45, 'ARTERIAL', 'asphalt', 0.42, 4.0),

-- Hawthorne Bridge approach (Moderate curvature 0.30-0.50)
(10010, ST_GeomFromText('LINESTRING(-122.6725 45.5095, -122.6710 45.5103)', 4326), '891e83d373cffff', 240, 40, 'ARTERIAL', 'asphalt', 0.32, 2.8),
(10011, ST_GeomFromText('LINESTRING(-122.6710 45.5103, -122.6695 45.5111)', 4326), '891e83d373cffff', 260, 40, 'ARTERIAL', 'asphalt', 0.38, 3.1),
(10012, ST_GeomFromText('LINESTRING(-122.6695 45.5111, -122.6680 45.5119)', 4326), '891e83d373cffff', 250, 45, 'ARTERIAL', 'asphalt', 0.44, 3.6),
(10013, ST_GeomFromText('LINESTRING(-122.6680 45.5119, -122.6665 45.5127)', 4326), '891e83d373cffff', 270, 45, 'ARTERIAL', 'asphalt', 0.48, 4.1),

-- Burnside Bridge route (Moderate curvature 0.28-0.48)
(10014, ST_GeomFromText('LINESTRING(-122.6815 45.5245, -122.6800 45.5253)', 4326), '891e83d373cffff', 230, 40, 'ARTERIAL', 'asphalt', 0.30, 3.0),
(10015, ST_GeomFromText('LINESTRING(-122.6800 45.5253, -122.6785 45.5261)', 4326), '891e83d373cffff', 250, 40, 'ARTERIAL', 'asphalt', 0.35, 3.3),
(10016, ST_GeomFromText('LINESTRING(-122.6785 45.5261, -122.6770 45.5269)', 4326), '891e83d373cffff', 260, 45, 'ARTERIAL', 'asphalt', 0.42, 3.9),
(10017, ST_GeomFromText('LINESTRING(-122.6770 45.5269, -122.6755 45.5277)', 4326), '891e83d373cffff', 270, 45, 'ARTERIAL', 'asphalt', 0.46, 4.2),

-- Downtown through Pearl District (Low-moderate curvature 0.12-0.32)
(10018, ST_GeomFromText('LINESTRING(-122.6850 45.5290, -122.6840 45.5298)', 4326), '891e83d373cffff', 140, 25, 'COLLECTOR', 'asphalt', 0.16, 1.5),
(10019, ST_GeomFromText('LINESTRING(-122.6840 45.5298, -122.6830 45.5306)', 4326), '891e83d373cffff', 150, 25, 'COLLECTOR', 'asphalt', 0.18, 1.8),
(10020, ST_GeomFromText('LINESTRING(-122.6830 45.5306, -122.6820 45.5314)', 4326), '891e83d373cffff', 160, 30, 'COLLECTOR', 'asphalt', 0.22, 2.0),
(10021, ST_GeomFromText('LINESTRING(-122.6820 45.5314, -122.6810 45.5322)', 4326), '891e83d373cffff', 155, 30, 'COLLECTOR', 'asphalt', 0.24, 2.2),
(10022, ST_GeomFromText('LINESTRING(-122.6810 45.5322, -122.6800 45.5330)', 4326), '891e83d373cffff', 170, 30, 'COLLECTOR', 'asphalt', 0.26, 2.4),

-- Westside urban connections (Moderate curvature 0.18-0.38)
(10023, ST_GeomFromText('LINESTRING(-122.6950 45.5200, -122.6935 45.5208)', 4326), '891e83d373cffff', 220, 35, 'ARTERIAL', 'asphalt', 0.20, 2.6),
(10024, ST_GeomFromText('LINESTRING(-122.6935 45.5208, -122.6920 45.5216)', 4326), '891e83d373cffff', 240, 35, 'ARTERIAL', 'asphalt', 0.25, 2.9),
(10025, ST_GeomFromText('LINESTRING(-122.6920 45.5216, -122.6905 45.5224)', 4326), '891e83d373cffff', 250, 40, 'ARTERIAL', 'asphalt', 0.30, 3.2),
(10026, ST_GeomFromText('LINESTRING(-122.6905 45.5224, -122.6890 45.5232)', 4326), '891e83d373cffff', 260, 40, 'ARTERIAL', 'asphalt', 0.34, 3.5),
(10027, ST_GeomFromText('LINESTRING(-122.6890 45.5232, -122.6875 45.5240)', 4326), '891e83d373cffff', 270, 40, 'ARTERIAL', 'asphalt', 0.38, 3.8),

-- Southeast Hawthorne neighborhood (Moderate curvature 0.32-0.52)
(10028, ST_GeomFromText('LINESTRING(-122.6585 45.5045, -122.6570 45.5053)', 4326), '891e83d373cffff', 240, 35, 'COLLECTOR', 'asphalt', 0.35, 3.1),
(10029, ST_GeomFromText('LINESTRING(-122.6570 45.5053, -122.6555 45.5061)', 4326), '891e83d373cffff', 260, 35, 'COLLECTOR', 'asphalt', 0.38, 3.4),
(10030, ST_GeomFromText('LINESTRING(-122.6555 45.5061, -122.6540 45.5069)', 4326), '891e83d373cffff', 250, 40, 'COLLECTOR', 'asphalt', 0.42, 3.7),
(10031, ST_GeomFromText('LINESTRING(-122.6540 45.5069, -122.6525 45.5077)', 4326), '891e83d373cffff', 280, 40, 'COLLECTOR', 'asphalt', 0.48, 4.2),
(10032, ST_GeomFromText('LINESTRING(-122.6525 45.5077, -122.6510 45.5085)', 4326), '891e83d373cffff', 270, 30, 'LOCAL', 'asphalt', 0.52, 4.5),

-- Willamette River waterfront - East side (Moderate-high curvature 0.38-0.62)
(10033, ST_GeomFromText('LINESTRING(-122.6620 45.5150, -122.6605 45.5158)', 4326), '891e83d373cffff', 250, 25, 'LOCAL', 'asphalt', 0.42, 1.2),
(10034, ST_GeomFromText('LINESTRING(-122.6605 45.5158, -122.6590 45.5166)', 4326), '891e83d373cffff', 270, 25, 'LOCAL', 'asphalt', 0.48, 1.5),
(10035, ST_GeomFromText('LINESTRING(-122.6590 45.5166, -122.6575 45.5174)', 4326), '891e83d373cffff', 280, 20, 'LOCAL', 'asphalt', 0.55, 0.8),
(10036, ST_GeomFromText('LINESTRING(-122.6575 45.5174, -122.6560 45.5182)', 4326), '891e83d373cffff', 290, 20, 'LOCAL', 'asphalt', 0.58, 0.5),
(10037, ST_GeomFromText('LINESTRING(-122.6560 45.5182, -122.6545 45.5190)', 4326), '891e83d373cffff', 310, 20, 'LOCAL', 'asphalt', 0.62, 1.1),

-- South Waterfront Park routes (High curvature 0.45-0.72)
(10038, ST_GeomFromText('LINESTRING(-122.6700 45.5050, -122.6685 45.5058)', 4326), '891e83d373cffff', 260, 20, 'LOCAL', 'asphalt', 0.48, 0.9),
(10039, ST_GeomFromText('LINESTRING(-122.6685 45.5058, -122.6670 45.5066)', 4326), '891e83d373cffff', 280, 20, 'LOCAL', 'asphalt', 0.52, 1.2),
(10040, ST_GeomFromText('LINESTRING(-122.6670 45.5066, -122.6655 45.5074)', 4326), '891e83d373cffff', 300, 15, 'LOCAL', 'asphalt', 0.58, 1.5),
(10041, ST_GeomFromText('LINESTRING(-122.6655 45.5074, -122.6640 45.5082)', 4326), '891e83d373cffff', 290, 15, 'LOCAL', 'asphalt', 0.65, 1.8),
(10042, ST_GeomFromText('LINESTRING(-122.6640 45.5082, -122.6625 45.5090)', 4326), '891e83d373cffff', 310, 15, 'LOCAL', 'asphalt', 0.72, 2.2),

-- Tom McCall Waterfront Park North (Moderate-high curvature 0.35-0.58)
(10043, ST_GeomFromText('LINESTRING(-122.6700 45.5250, -122.6685 45.5258)', 4326), '891e83d373cffff', 280, 25, 'LOCAL', 'asphalt', 0.38, 1.4),
(10044, ST_GeomFromText('LINESTRING(-122.6685 45.5258, -122.6670 45.5266)', 4326), '891e83d373cffff', 300, 25, 'LOCAL', 'asphalt', 0.42, 1.6),
(10045, ST_GeomFromText('LINESTRING(-122.6670 45.5266, -122.6655 45.5274)', 4326), '891e83d373cffff', 310, 20, 'LOCAL', 'asphalt', 0.48, 1.9),
(10046, ST_GeomFromText('LINESTRING(-122.6655 45.5274, -122.6640 45.5282)', 4326), '891e83d373cffff', 320, 20, 'LOCAL', 'asphalt', 0.52, 2.1),
(10047, ST_GeomFromText('LINESTRING(-122.6640 45.5282, -122.6625 45.5290)', 4326), '891e83d373cffff', 330, 20, 'LOCAL', 'asphalt', 0.58, 2.4),

-- Forest Park - Northern access (High curvature 0.50-0.78)
(10048, ST_GeomFromText('LINESTRING(-122.7100 45.5550, -122.7085 45.5558)', 4326), '891e83d373cffff', 280, 20, 'LOCAL', 'gravel', 0.52, 5.2),
(10049, ST_GeomFromText('LINESTRING(-122.7085 45.5558, -122.7070 45.5566)', 4326), '891e83d373cffff', 300, 20, 'LOCAL', 'gravel', 0.58, 5.8),
(10050, ST_GeomFromText('LINESTRING(-122.7070 45.5566, -122.7055 45.5574)', 4326), '891e83d373cffff', 320, 15, 'LOCAL', 'gravel', 0.65, 6.5),
(10051, ST_GeomFromText('LINESTRING(-122.7055 45.5574, -122.7040 45.5582)', 4326), '891e83d373cffff', 310, 15, 'LOCAL', 'gravel', 0.72, 7.1),
(10052, ST_GeomFromText('LINESTRING(-122.7040 45.5582, -122.7025 45.5590)', 4326), '891e83d373cffff', 340, 15, 'LOCAL', 'gravel', 0.78, 7.8),

-- Forest Park - Western loop (High curvature 0.58-0.85)
(10053, ST_GeomFromText('LINESTRING(-122.7150 45.5600, -122.7135 45.5608)', 4326), '891e83d373cffff', 300, 15, 'LOCAL', 'gravel', 0.62, 6.2),
(10054, ST_GeomFromText('LINESTRING(-122.7135 45.5608, -122.7120 45.5616)', 4326), '891e83d373cffff', 320, 15, 'LOCAL', 'gravel', 0.68, 6.8),
(10055, ST_GeomFromText('LINESTRING(-122.7120 45.5616, -122.7105 45.5624)', 4326), '891e83d373cffff', 340, 15, 'LOCAL', 'gravel', 0.75, 7.5),
(10056, ST_GeomFromText('LINESTRING(-122.7105 45.5624, -122.7090 45.5632)', 4326), '891e83d373cffff', 350, 15, 'LOCAL', 'gravel', 0.80, 8.2),
(10057, ST_GeomFromText('LINESTRING(-122.7090 45.5632, -122.7075 45.5640)', 4326), '891e83d373cffff', 360, 10, 'LOCAL', 'gravel', 0.85, 8.9),

-- Forest Park - Central trails (High curvature 0.55-0.78)
(10058, ST_GeomFromText('LINESTRING(-122.7200 45.5570, -122.7185 45.5578)', 4326), '891e83d373cffff', 310, 15, 'LOCAL', 'gravel', 0.58, 6.1),
(10059, ST_GeomFromText('LINESTRING(-122.7185 45.5578, -122.7170 45.5586)', 4326), '891e83d373cffff', 330, 15, 'LOCAL', 'gravel', 0.65, 6.9),
(10060, ST_GeomFromText('LINESTRING(-122.7170 45.5586, -122.7155 45.5594)', 4326), '891e83d373cffff', 350, 15, 'LOCAL', 'gravel', 0.72, 7.6),
(10061, ST_GeomFromText('LINESTRING(-122.7155 45.5594, -122.7140 45.5602)', 4326), '891e83d373cffff', 360, 15, 'LOCAL', 'gravel', 0.76, 8.1),
(10062, ST_GeomFromText('LINESTRING(-122.7140 45.5602, -122.7125 45.5610)', 4326), '891e83d373cffff', 370, 10, 'LOCAL', 'gravel', 0.78, 8.5);
