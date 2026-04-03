-- Flyway Migration V5: Insert sample Portland, Oregon road segments
-- This script populates ~150 realistic road segments covering Portland metro area
-- Coordinates centered around Portland (45.5 N, 122.7 W)

INSERT INTO road_segments (id, start_lat, start_lon, end_lat, end_lon, length_meters, road_type, scenic_score, created_at) 
VALUES
-- Downtown Core - Urban Grid (Low scenic 0.3-0.5)
(gen_random_uuid(), 45.5157, -122.6789, 45.5165, -122.6795, 150, 'ARTERIAL', 0.42, NOW()),
(gen_random_uuid(), 45.5165, -122.6795, 45.5172, -122.6801, 180, 'ARTERIAL', 0.45, NOW()),
(gen_random_uuid(), 45.5172, -122.6801, 45.5180, -122.6807, 160, 'ARTERIAL', 0.43, NOW()),
(gen_random_uuid(), 45.5180, -122.6807, 45.5187, -122.6813, 170, 'ARTERIAL', 0.40, NOW()),
(gen_random_uuid(), 45.5187, -122.6813, 45.5195, -122.6819, 175, 'ARTERIAL', 0.38, NOW()),

-- Morrison Bridge area connections (Moderate scenic 0.5-0.65)
(gen_random_uuid(), 45.5200, -122.6780, 45.5208, -122.6765, 220, 'ARTERIAL', 0.55, NOW()),
(gen_random_uuid(), 45.5208, -122.6765, 45.5216, -122.6750, 240, 'ARTERIAL', 0.58, NOW()),
(gen_random_uuid(), 45.5216, -122.6750, 45.5224, -122.6735, 260, 'ARTERIAL', 0.60, NOW()),
(gen_random_uuid(), 45.5224, -122.6735, 45.5232, -122.6720, 250, 'ARTERIAL', 0.62, NOW()),

-- Hawthorne Bridge approach (Moderate scenic 0.55-0.68)
(gen_random_uuid(), 45.5095, -122.6725, 45.5103, -122.6710, 240, 'ARTERIAL', 0.58, NOW()),
(gen_random_uuid(), 45.5103, -122.6710, 45.5111, -122.6695, 260, 'ARTERIAL', 0.61, NOW()),
(gen_random_uuid(), 45.5111, -122.6695, 45.5119, -122.6680, 250, 'ARTERIAL', 0.65, NOW()),
(gen_random_uuid(), 45.5119, -122.6680, 45.5127, -122.6665, 270, 'ARTERIAL', 0.68, NOW()),

-- Burnside Bridge route (Moderate scenic 0.52-0.67)
(gen_random_uuid(), 45.5245, -122.6815, 45.5253, -122.6800, 230, 'ARTERIAL', 0.54, NOW()),
(gen_random_uuid(), 45.5253, -122.6800, 45.5261, -122.6785, 250, 'ARTERIAL', 0.57, NOW()),
(gen_random_uuid(), 45.5261, -122.6785, 45.5269, -122.6770, 260, 'ARTERIAL', 0.62, NOW()),
(gen_random_uuid(), 45.5269, -122.6770, 45.5277, -122.6755, 270, 'ARTERIAL', 0.67, NOW()),

-- Downtown through Pearl District (Low-moderate scenic 0.43-0.58)
(gen_random_uuid(), 45.5290, -122.6850, 45.5298, -122.6840, 140, 'COLLECTOR', 0.48, NOW()),
(gen_random_uuid(), 45.5298, -122.6840, 45.5306, -122.6830, 150, 'COLLECTOR', 0.50, NOW()),
(gen_random_uuid(), 45.5306, -122.6830, 45.5314, -122.6820, 160, 'COLLECTOR', 0.52, NOW()),
(gen_random_uuid(), 45.5314, -122.6820, 45.5322, -122.6810, 155, 'COLLECTOR', 0.55, NOW()),
(gen_random_uuid(), 45.5322, -122.6810, 45.5330, -122.6800, 170, 'COLLECTOR', 0.58, NOW()),

-- Westside urban connections (Moderate scenic 0.45-0.62)
(gen_random_uuid(), 45.5200, -122.6950, 45.5208, -122.6935, 220, 'ARTERIAL', 0.48, NOW()),
(gen_random_uuid(), 45.5208, -122.6935, 45.5216, -122.6920, 240, 'ARTERIAL', 0.51, NOW()),
(gen_random_uuid(), 45.5216, -122.6920, 45.5224, -122.6905, 250, 'ARTERIAL', 0.55, NOW()),
(gen_random_uuid(), 45.5224, -122.6905, 45.5232, -122.6890, 260, 'ARTERIAL', 0.58, NOW()),
(gen_random_uuid(), 45.5232, -122.6890, 45.5240, -122.6875, 270, 'ARTERIAL', 0.62, NOW()),

-- Southeast Hawthorne neighborhood (Moderate scenic 0.55-0.70)
(gen_random_uuid(), 45.5045, -122.6585, 45.5053, -122.6570, 240, 'COLLECTOR', 0.60, NOW()),
(gen_random_uuid(), 45.5053, -122.6570, 45.5061, -122.6555, 260, 'COLLECTOR', 0.63, NOW()),
(gen_random_uuid(), 45.5061, -122.6555, 45.5069, -122.6540, 250, 'COLLECTOR', 0.65, NOW()),
(gen_random_uuid(), 45.5069, -122.6540, 45.5077, -122.6525, 280, 'COLLECTOR', 0.68, NOW()),
(gen_random_uuid(), 45.5077, -122.6525, 45.5085, -122.6510, 270, 'LOCAL', 0.70, NOW()),

-- Willamette River waterfront - East side (Moderate-high scenic 0.62-0.78)
(gen_random_uuid(), 45.5150, -122.6620, 45.5158, -122.6605, 250, 'LOCAL', 0.65, NOW()),
(gen_random_uuid(), 45.5158, -122.6605, 45.5166, -122.6590, 270, 'LOCAL', 0.68, NOW()),
(gen_random_uuid(), 45.5166, -122.6590, 45.5174, -122.6575, 280, 'LOCAL', 0.72, NOW()),
(gen_random_uuid(), 45.5174, -122.6575, 45.5182, -122.6560, 290, 'LOCAL', 0.75, NOW()),
(gen_random_uuid(), 45.5182, -122.6560, 45.5190, -122.6545, 310, 'LOCAL', 0.78, NOW()),

-- South Waterfront Park routes (High scenic 0.70-0.82)
(gen_random_uuid(), 45.5050, -122.6700, 45.5058, -122.6685, 260, 'LOCAL', 0.72, NOW()),
(gen_random_uuid(), 45.5058, -122.6685, 45.5066, -122.6670, 280, 'LOCAL', 0.75, NOW()),
(gen_random_uuid(), 45.5066, -122.6670, 45.5074, -122.6655, 300, 'LOCAL', 0.78, NOW()),
(gen_random_uuid(), 45.5074, -122.6655, 45.5082, -122.6640, 290, 'LOCAL', 0.80, NOW()),
(gen_random_uuid(), 45.5082, -122.6640, 45.5090, -122.6625, 310, 'LOCAL', 0.82, NOW()),

-- Tom McCall Waterfront Park North (Moderate-high scenic 0.60-0.75)
(gen_random_uuid(), 45.5250, -122.6700, 45.5258, -122.6685, 280, 'LOCAL', 0.62, NOW()),
(gen_random_uuid(), 45.5258, -122.6685, 45.5266, -122.6670, 300, 'LOCAL', 0.65, NOW()),
(gen_random_uuid(), 45.5266, -122.6670, 45.5274, -122.6655, 310, 'LOCAL', 0.68, NOW()),
(gen_random_uuid(), 45.5274, -122.6655, 45.5282, -122.6640, 320, 'LOCAL', 0.72, NOW()),
(gen_random_uuid(), 45.5282, -122.6640, 45.5290, -122.6625, 330, 'LOCAL', 0.75, NOW()),

-- Forest Park - Northern access (High scenic 0.75-0.88)
(gen_random_uuid(), 45.5550, -122.7100, 45.5558, -122.7085, 280, 'LOCAL', 0.76, NOW()),
(gen_random_uuid(), 45.5558, -122.7085, 45.5566, -122.7070, 300, 'LOCAL', 0.79, NOW()),
(gen_random_uuid(), 45.5566, -122.7070, 45.5574, -122.7055, 320, 'LOCAL', 0.82, NOW()),
(gen_random_uuid(), 45.5574, -122.7055, 45.5582, -122.7040, 310, 'LOCAL', 0.84, NOW()),
(gen_random_uuid(), 45.5582, -122.7040, 45.5590, -122.7025, 340, 'LOCAL', 0.86, NOW()),

-- Forest Park - Western loop (High scenic 0.80-0.90)
(gen_random_uuid(), 45.5600, -122.7150, 45.5608, -122.7135, 300, 'LOCAL', 0.81, NOW()),
(gen_random_uuid(), 45.5608, -122.7135, 45.5616, -122.7120, 320, 'LOCAL', 0.83, NOW()),
(gen_random_uuid(), 45.5616, -122.7120, 45.5624, -122.7105, 340, 'LOCAL', 0.85, NOW()),
(gen_random_uuid(), 45.5624, -122.7105, 45.5632, -122.7090, 350, 'LOCAL', 0.87, NOW()),
(gen_random_uuid(), 45.5632, -122.7090, 45.5640, -122.7075, 360, 'LOCAL', 0.90, NOW()),

-- Forest Park - Central trails (High scenic 0.78-0.88)
(gen_random_uuid(), 45.5570, -122.7200, 45.5578, -122.7185, 310, 'LOCAL', 0.78, NOW()),
(gen_random_uuid(), 45.5578, -122.7185, 45.5586, -122.7170, 330, 'LOCAL', 0.81, NOW()),
(gen_random_uuid(), 45.5586, -122.7170, 45.5594, -122.7155, 350, 'LOCAL', 0.84, NOW()),
(gen_random_uuid(), 45.5594, -122.7155, 45.5602, -122.7140, 360, 'LOCAL', 0.87, NOW()),
(gen_random_uuid(), 45.5602, -122.7140, 45.5610, -122.7125, 370, 'LOCAL', 0.88, NOW()),

-- Forest Park - Eastern side (High scenic 0.76-0.85)
(gen_random_uuid(), 45.5520, -122.6950, 45.5528, -122.6935, 290, 'LOCAL', 0.77, NOW()),
(gen_random_uuid(), 45.5528, -122.6935, 45.5536, -122.6920, 310, 'LOCAL', 0.80, NOW()),
(gen_random_uuid(), 45.5536, -122.6920, 45.5544, -122.6905, 330, 'LOCAL', 0.82, NOW()),
(gen_random_uuid(), 45.5544, -122.6905, 45.5552, -122.6890, 350, 'LOCAL', 0.84, NOW()),
(gen_random_uuid(), 45.5552, -122.6890, 45.5560, -122.6875, 360, 'LOCAL', 0.85, NOW()),

-- Mount Tabor - Northern approach (High scenic 0.80-0.88)
(gen_random_uuid(), 45.5100, -122.5950, 45.5108, -122.5935, 300, 'LOCAL', 0.81, NOW()),
(gen_random_uuid(), 45.5108, -122.5935, 45.5116, -122.5920, 320, 'LOCAL', 0.83, NOW()),
(gen_random_uuid(), 45.5116, -122.5920, 45.5124, -122.5905, 340, 'LOCAL', 0.85, NOW()),
(gen_random_uuid(), 45.5124, -122.5905, 45.5132, -122.5890, 350, 'LOCAL', 0.87, NOW()),
(gen_random_uuid(), 45.5132, -122.5890, 45.5140, -122.5875, 360, 'LOCAL', 0.88, NOW()),

-- Mount Tabor - Western loop (High scenic 0.82-0.90)
(gen_random_uuid(), 45.5080, -122.6000, 45.5088, -122.5985, 310, 'LOCAL', 0.83, NOW()),
(gen_random_uuid(), 45.5088, -122.5985, 45.5096, -122.5970, 330, 'LOCAL', 0.85, NOW()),
(gen_random_uuid(), 45.5096, -122.5970, 45.5104, -122.5955, 350, 'LOCAL', 0.87, NOW()),
(gen_random_uuid(), 45.5104, -122.5955, 45.5112, -122.5940, 360, 'LOCAL', 0.89, NOW()),
(gen_random_uuid(), 45.5112, -122.5940, 45.5120, -122.5925, 370, 'LOCAL', 0.90, NOW()),

-- Mount Tabor - Southern scenic loop (High scenic 0.80-0.88)
(gen_random_uuid(), 45.5020, -122.5980, 45.5028, -122.5965, 300, 'LOCAL', 0.81, NOW()),
(gen_random_uuid(), 45.5028, -122.5965, 45.5036, -122.5950, 320, 'LOCAL', 0.83, NOW()),
(gen_random_uuid(), 45.5036, -122.5950, 45.5044, -122.5935, 340, 'LOCAL', 0.85, NOW()),
(gen_random_uuid(), 45.5044, -122.5935, 45.5052, -122.5920, 350, 'LOCAL', 0.86, NOW()),
(gen_random_uuid(), 45.5052, -122.5920, 45.5060, -122.5905, 360, 'LOCAL', 0.88, NOW()),

-- Northeast urban to Forest Park transitions (Moderate-high scenic 0.58-0.72)
(gen_random_uuid(), 45.5450, -122.6850, 45.5458, -122.6835, 270, 'COLLECTOR', 0.62, NOW()),
(gen_random_uuid(), 45.5458, -122.6835, 45.5466, -122.6820, 290, 'COLLECTOR', 0.65, NOW()),
(gen_random_uuid(), 45.5466, -122.6820, 45.5474, -122.6805, 310, 'COLLECTOR', 0.68, NOW()),
(gen_random_uuid(), 45.5474, -122.6805, 45.5482, -122.6790, 320, 'COLLECTOR', 0.70, NOW()),
(gen_random_uuid(), 45.5482, -122.6790, 45.5490, -122.6775, 330, 'COLLECTOR', 0.72, NOW()),

-- Hollywood Park area (Moderate scenic 0.60-0.75)
(gen_random_uuid(), 45.5380, -122.6650, 45.5388, -122.6635, 280, 'LOCAL', 0.62, NOW()),
(gen_random_uuid(), 45.5388, -122.6635, 45.5396, -122.6620, 300, 'LOCAL', 0.65, NOW()),
(gen_random_uuid(), 45.5396, -122.6620, 45.5404, -122.6605, 320, 'LOCAL', 0.68, NOW()),
(gen_random_uuid(), 45.5404, -122.6605, 45.5412, -122.6590, 330, 'LOCAL', 0.72, NOW()),
(gen_random_uuid(), 45.5412, -122.6590, 45.5420, -122.6575, 340, 'LOCAL', 0.75, NOW()),

-- Columbia River Gorge approach - Eastern (High scenic 0.75-0.87)
(gen_random_uuid(), 45.5300, -122.5700, 45.5308, -122.5685, 300, 'LOCAL', 0.76, NOW()),
(gen_random_uuid(), 45.5308, -122.5685, 45.5316, -122.5670, 320, 'LOCAL', 0.79, NOW()),
(gen_random_uuid(), 45.5316, -122.5670, 45.5324, -122.5655, 340, 'LOCAL', 0.82, NOW()),
(gen_random_uuid(), 45.5324, -122.5655, 45.5332, -122.5640, 350, 'LOCAL', 0.85, NOW()),
(gen_random_uuid(), 45.5332, -122.5640, 45.5340, -122.5625, 360, 'LOCAL', 0.87, NOW()),

-- Industrial southeast corridors (Low scenic 0.32-0.48)
(gen_random_uuid(), 45.4950, -122.6400, 45.4958, -122.6385, 250, 'ARTERIAL', 0.35, NOW()),
(gen_random_uuid(), 45.4958, -122.6385, 45.4966, -122.6370, 270, 'ARTERIAL', 0.38, NOW()),
(gen_random_uuid(), 45.4966, -122.6370, 45.4974, -122.6355, 280, 'ARTERIAL', 0.40, NOW()),
(gen_random_uuid(), 45.4974, -122.6355, 45.4982, -122.6340, 290, 'ARTERIAL', 0.42, NOW()),
(gen_random_uuid(), 45.4982, -122.6340, 45.4990, -122.6325, 300, 'ARTERIAL', 0.45, NOW()),

-- Industrial area north (Low scenic 0.30-0.45)
(gen_random_uuid(), 45.5550, -122.6500, 45.5558, -122.6485, 280, 'ARTERIAL', 0.33, NOW()),
(gen_random_uuid(), 45.5558, -122.6485, 45.5566, -122.6470, 300, 'ARTERIAL', 0.36, NOW()),
(gen_random_uuid(), 45.5566, -122.6470, 45.5574, -122.6455, 320, 'ARTERIAL', 0.40, NOW()),
(gen_random_uuid(), 45.5574, -122.6455, 45.5582, -122.6440, 330, 'ARTERIAL', 0.43, NOW()),
(gen_random_uuid(), 45.5582, -122.6440, 45.5590, -122.6425, 340, 'ARTERIAL', 0.45, NOW()),

-- Residential Northeast neighborhoods (Moderate scenic 0.50-0.65)
(gen_random_uuid(), 45.5350, -122.6350, 45.5358, -122.6335, 260, 'COLLECTOR', 0.52, NOW()),
(gen_random_uuid(), 45.5358, -122.6335, 45.5366, -122.6320, 280, 'COLLECTOR', 0.55, NOW()),
(gen_random_uuid(), 45.5366, -122.6320, 45.5374, -122.6305, 300, 'COLLECTOR', 0.58, NOW()),
(gen_random_uuid(), 45.5374, -122.6305, 45.5382, -122.6290, 310, 'COLLECTOR', 0.61, NOW()),
(gen_random_uuid(), 45.5382, -122.6290, 45.5390, -122.6275, 320, 'LOCAL', 0.65, NOW()),

-- Residential Southwest neighborhoods (Moderate scenic 0.48-0.62)
(gen_random_uuid(), 45.5150, -122.7050, 45.5158, -122.7035, 270, 'COLLECTOR', 0.51, NOW()),
(gen_random_uuid(), 45.5158, -122.7035, 45.5166, -122.7020, 290, 'COLLECTOR', 0.54, NOW()),
(gen_random_uuid(), 45.5166, -122.7020, 45.5174, -122.7005, 310, 'COLLECTOR', 0.57, NOW()),
(gen_random_uuid(), 45.5174, -122.7005, 45.5182, -122.6990, 320, 'COLLECTOR', 0.60, NOW()),
(gen_random_uuid(), 45.5182, -122.6990, 45.5190, -122.6975, 330, 'LOCAL', 0.62, NOW()),

-- Powell Butte area (High scenic 0.75-0.85)
(gen_random_uuid(), 45.4850, -122.5850, 45.4858, -122.5835, 280, 'LOCAL', 0.76, NOW()),
(gen_random_uuid(), 45.4858, -122.5835, 45.4866, -122.5820, 300, 'LOCAL', 0.79, NOW()),
(gen_random_uuid(), 45.4866, -122.5820, 45.4874, -122.5805, 320, 'LOCAL', 0.82, NOW()),
(gen_random_uuid(), 45.4874, -122.5805, 45.4882, -122.5790, 340, 'LOCAL', 0.84, NOW()),
(gen_random_uuid(), 45.4882, -122.5790, 45.4890, -122.5775, 350, 'LOCAL', 0.85, NOW()),

-- Gateway/Parkrose neighborhood (Moderate scenic 0.54-0.68)
(gen_random_uuid(), 45.5250, -122.6050, 45.5258, -122.6035, 270, 'COLLECTOR', 0.56, NOW()),
(gen_random_uuid(), 45.5258, -122.6035, 45.5266, -122.6020, 290, 'COLLECTOR', 0.59, NOW()),
(gen_random_uuid(), 45.5266, -122.6020, 45.5274, -122.6005, 310, 'COLLECTOR', 0.62, NOW()),
(gen_random_uuid(), 45.5274, -122.6005, 45.5282, -122.5990, 320, 'COLLECTOR', 0.65, NOW()),
(gen_random_uuid(), 45.5282, -122.5990, 45.5290, -122.5975, 330, 'LOCAL', 0.68, NOW()),

-- Springwater Corridor Trail sections (High scenic 0.72-0.86)
(gen_random_uuid(), 45.5200, -122.6450, 45.5208, -122.6435, 280, 'LOCAL', 0.74, NOW()),
(gen_random_uuid(), 45.5208, -122.6435, 45.5216, -122.6420, 300, 'LOCAL', 0.76, NOW()),
(gen_random_uuid(), 45.5216, -122.6420, 45.5224, -122.6405, 320, 'LOCAL', 0.79, NOW()),
(gen_random_uuid(), 45.5224, -122.6405, 45.5232, -122.6390, 340, 'LOCAL', 0.82, NOW()),
(gen_random_uuid(), 45.5232, -122.6390, 45.5240, -122.6375, 350, 'LOCAL', 0.84, NOW()),
(gen_random_uuid(), 45.5240, -122.6375, 45.5248, -122.6360, 360, 'LOCAL', 0.86, NOW()),

-- Lost Lake area (High scenic 0.78-0.90)
(gen_random_uuid(), 45.4700, -122.0950, 45.4708, -122.0935, 300, 'LOCAL', 0.80, NOW()),
(gen_random_uuid(), 45.4708, -122.0935, 45.4716, -122.0920, 320, 'LOCAL', 0.83, NOW()),
(gen_random_uuid(), 45.4716, -122.0920, 45.4724, -122.0905, 340, 'LOCAL', 0.86, NOW()),
(gen_random_uuid(), 45.4724, -122.0905, 45.4732, -122.0890, 350, 'LOCAL', 0.88, NOW()),
(gen_random_uuid(), 45.4732, -122.0890, 45.4740, -122.0875, 360, 'LOCAL', 0.90, NOW()),

-- Trillium Lake scenic area (High scenic 0.85-0.95)
(gen_random_uuid(), 45.3900, -121.7850, 45.3908, -121.7835, 310, 'LOCAL', 0.87, NOW()),
(gen_random_uuid(), 45.3908, -121.7835, 45.3916, -121.7820, 330, 'LOCAL', 0.89, NOW()),
(gen_random_uuid(), 45.3916, -121.7820, 45.3924, -121.7805, 350, 'LOCAL', 0.92, NOW()),
(gen_random_uuid(), 45.3924, -121.7805, 45.3932, -121.7790, 360, 'LOCAL', 0.95, NOW()),

-- Oxbow Regional Park (High scenic 0.80-0.88)
(gen_random_uuid(), 45.7200, -122.4350, 45.7208, -122.4335, 300, 'LOCAL', 0.82, NOW()),
(gen_random_uuid(), 45.7208, -122.4335, 45.7216, -122.4320, 320, 'LOCAL', 0.84, NOW()),
(gen_random_uuid(), 45.7216, -122.4320, 45.7224, -122.4305, 340, 'LOCAL', 0.86, NOW()),
(gen_random_uuid(), 45.7224, -122.4305, 45.7232, -122.4290, 350, 'LOCAL', 0.88, NOW()),

-- Columbia River scenic loop (Very high scenic 0.82-0.92)
(gen_random_uuid(), 45.6100, -122.5200, 45.6108, -122.5185, 320, 'LOCAL', 0.84, NOW()),
(gen_random_uuid(), 45.6108, -122.5185, 45.6116, -122.5170, 340, 'LOCAL', 0.86, NOW()),
(gen_random_uuid(), 45.6116, -122.5170, 45.6124, -122.5155, 360, 'LOCAL', 0.88, NOW()),
(gen_random_uuid(), 45.6124, -122.5155, 45.6132, -122.5140, 370, 'LOCAL', 0.90, NOW()),
(gen_random_uuid(), 45.6132, -122.5140, 45.6140, -122.5125, 380, 'LOCAL', 0.92, NOW()),

-- Sellwood Bridge area (Moderate-high scenic 0.62-0.75)
(gen_random_uuid(), 45.4750, -122.6250, 45.4758, -122.6235, 270, 'ARTERIAL', 0.64, NOW()),
(gen_random_uuid(), 45.4758, -122.6235, 45.4766, -122.6220, 290, 'ARTERIAL', 0.67, NOW()),
(gen_random_uuid(), 45.4766, -122.6220, 45.4774, -122.6205, 310, 'ARTERIAL', 0.70, NOW()),
(gen_random_uuid(), 45.4774, -122.6205, 45.4782, -122.6190, 320, 'ARTERIAL', 0.73, NOW()),
(gen_random_uuid(), 45.4782, -122.6190, 45.4790, -122.6175, 330, 'LOCAL', 0.75, NOW()),

-- St. Johns neighborhood (Moderate scenic 0.52-0.65)
(gen_random_uuid(), 45.5850, -122.7350, 45.5858, -122.7335, 280, 'COLLECTOR', 0.55, NOW()),
(gen_random_uuid(), 45.5858, -122.7335, 45.5866, -122.7320, 300, 'COLLECTOR', 0.58, NOW()),
(gen_random_uuid(), 45.5866, -122.7320, 45.5874, -122.7305, 320, 'COLLECTOR', 0.61, NOW()),
(gen_random_uuid(), 45.5874, -122.7305, 45.5882, -122.7290, 330, 'LOCAL', 0.64, NOW()),
(gen_random_uuid(), 45.5882, -122.7290, 45.5890, -122.7275, 340, 'LOCAL', 0.65, NOW()),

-- Outer Southeast Lents area (Moderate scenic 0.50-0.63)
(gen_random_uuid(), 45.4600, -122.6050, 45.4608, -122.6035, 260, 'COLLECTOR', 0.52, NOW()),
(gen_random_uuid(), 45.4608, -122.6035, 45.4616, -122.6020, 280, 'COLLECTOR', 0.55, NOW()),
(gen_random_uuid(), 45.4616, -122.6020, 45.4624, -122.6005, 300, 'COLLECTOR', 0.58, NOW()),
(gen_random_uuid(), 45.4624, -122.6005, 45.4632, -122.5990, 310, 'LOCAL', 0.61, NOW()),
(gen_random_uuid(), 45.4632, -122.5990, 45.4640, -122.5975, 320, 'LOCAL', 0.63, NOW()),

-- Gresham transition routes (Moderate scenic 0.54-0.68)
(gen_random_uuid(), 45.5050, -122.4350, 45.5058, -122.4335, 270, 'ARTERIAL', 0.56, NOW()),
(gen_random_uuid(), 45.5058, -122.4335, 45.5066, -122.4320, 290, 'ARTERIAL', 0.59, NOW()),
(gen_random_uuid(), 45.5066, -122.4320, 45.5074, -122.4305, 310, 'ARTERIAL', 0.62, NOW()),
(gen_random_uuid(), 45.5074, -122.4305, 45.5082, -122.4290, 320, 'COLLECTOR', 0.65, NOW()),
(gen_random_uuid(), 45.5082, -122.4290, 45.5090, -122.4275, 330, 'LOCAL', 0.68, NOW()),

-- Milwaukie corridor (Moderate scenic 0.50-0.64)
(gen_random_uuid(), 45.4350, -122.6450, 45.4358, -122.6435, 260, 'ARTERIAL', 0.52, NOW()),
(gen_random_uuid(), 45.4358, -122.6435, 45.4366, -122.6420, 280, 'ARTERIAL', 0.55, NOW()),
(gen_random_uuid(), 45.4366, -122.6420, 45.4374, -122.6405, 300, 'ARTERIAL', 0.58, NOW()),
(gen_random_uuid(), 45.4374, -122.6405, 45.4382, -122.6390, 310, 'COLLECTOR', 0.61, NOW()),
(gen_random_uuid(), 45.4382, -122.6390, 45.4390, -122.6375, 320, 'LOCAL', 0.64, NOW()),

-- Oregon City scenic approach (High scenic 0.72-0.83)
(gen_random_uuid(), 45.3550, -122.6100, 45.3558, -122.6085, 280, 'LOCAL', 0.74, NOW()),
(gen_random_uuid(), 45.3558, -122.6085, 45.3566, -122.6070, 300, 'LOCAL', 0.77, NOW()),
(gen_random_uuid(), 45.3566, -122.6070, 45.3574, -122.6055, 320, 'LOCAL', 0.80, NOW()),
(gen_random_uuid(), 45.3574, -122.6055, 45.3582, -122.6040, 330, 'LOCAL', 0.82, NOW()),
(gen_random_uuid(), 45.3582, -122.6040, 45.3590, -122.6025, 340, 'LOCAL', 0.83, NOW()),

-- Lake Oswego scenic loop (High scenic 0.75-0.85)
(gen_random_uuid(), 45.4100, -122.6700, 45.4108, -122.6685, 290, 'LOCAL', 0.77, NOW()),
(gen_random_uuid(), 45.4108, -122.6685, 45.4116, -122.6670, 310, 'LOCAL', 0.80, NOW()),
(gen_random_uuid(), 45.4116, -122.6670, 45.4124, -122.6655, 330, 'LOCAL', 0.82, NOW()),
(gen_random_uuid(), 45.4124, -122.6655, 45.4132, -122.6640, 340, 'LOCAL', 0.84, NOW()),
(gen_random_uuid(), 45.4132, -122.6640, 45.4140, -122.6625, 350, 'LOCAL', 0.85, NOW()),

-- Tigard transition routes (Moderate scenic 0.48-0.62)
(gen_random_uuid(), 45.4250, -122.7550, 45.4258, -122.7535, 270, 'ARTERIAL', 0.50, NOW()),
(gen_random_uuid(), 45.4258, -122.7535, 45.4266, -122.7520, 290, 'ARTERIAL', 0.53, NOW()),
(gen_random_uuid(), 45.4266, -122.7520, 45.4274, -122.7505, 310, 'ARTERIAL', 0.56, NOW()),
(gen_random_uuid(), 45.4274, -122.7505, 45.4282, -122.7490, 320, 'COLLECTOR', 0.59, NOW()),
(gen_random_uuid(), 45.4282, -122.7490, 45.4290, -122.7475, 330, 'LOCAL', 0.62, NOW()),

-- Beaverton connectivity (Moderate scenic 0.46-0.60)
(gen_random_uuid(), 45.4950, -122.7950, 45.4958, -122.7935, 280, 'ARTERIAL', 0.48, NOW()),
(gen_random_uuid(), 45.4958, -122.7935, 45.4966, -122.7920, 300, 'ARTERIAL', 0.51, NOW()),
(gen_random_uuid(), 45.4966, -122.7920, 45.4974, -122.7905, 320, 'ARTERIAL', 0.54, NOW()),
(gen_random_uuid(), 45.4974, -122.7905, 45.4982, -122.7890, 330, 'COLLECTOR', 0.57, NOW()),
(gen_random_uuid(), 45.4982, -122.7890, 45.4990, -122.7875, 340, 'LOCAL', 0.60, NOW()),

-- Hillsboro rural scenic (Moderate-high scenic 0.60-0.72)
(gen_random_uuid(), 45.5450, -123.0050, 45.5458, -123.0035, 290, 'COLLECTOR', 0.62, NOW()),
(gen_random_uuid(), 45.5458, -123.0035, 45.5466, -123.0020, 310, 'COLLECTOR', 0.65, NOW()),
(gen_random_uuid(), 45.5466, -123.0020, 45.5474, -123.0005, 330, 'COLLECTOR', 0.68, NOW()),
(gen_random_uuid(), 45.5474, -123.0005, 45.5482, -122.9990, 340, 'LOCAL', 0.70, NOW()),
(gen_random_uuid(), 45.5482, -122.9990, 45.5490, -122.9975, 350, 'LOCAL', 0.72, NOW()),

-- Vernonia area loop (High scenic 0.76-0.87)
(gen_random_uuid(), 45.8550, -123.1950, 45.8558, -123.1935, 300, 'LOCAL', 0.78, NOW()),
(gen_random_uuid(), 45.8558, -123.1935, 45.8566, -123.1920, 320, 'LOCAL', 0.81, NOW()),
(gen_random_uuid(), 45.8566, -123.1920, 45.8574, -123.1905, 340, 'LOCAL', 0.84, NOW()),
(gen_random_uuid(), 45.8574, -123.1905, 45.8582, -123.1890, 350, 'LOCAL', 0.86, NOW()),
(gen_random_uuid(), 45.8582, -123.1890, 45.8590, -123.1875, 360, 'LOCAL', 0.87, NOW()),

-- Sandy River scenic (High scenic 0.79-0.88)
(gen_random_uuid(), 45.3850, -122.2250, 45.3858, -122.2235, 310, 'LOCAL', 0.81, NOW()),
(gen_random_uuid(), 45.3858, -122.2235, 45.3866, -122.2220, 330, 'LOCAL', 0.83, NOW()),
(gen_random_uuid(), 45.3866, -122.2220, 45.3874, -122.2205, 350, 'LOCAL', 0.85, NOW()),
(gen_random_uuid(), 45.3874, -122.2205, 45.3882, -122.2190, 360, 'LOCAL', 0.87, NOW()),
(gen_random_uuid(), 45.3882, -122.2190, 45.3890, -122.2175, 370, 'LOCAL', 0.88, NOW()),

-- Hood River scenic approach (Very high scenic 0.80-0.92)
(gen_random_uuid(), 45.7050, -121.5150, 45.7058, -121.5135, 320, 'LOCAL', 0.82, NOW()),
(gen_random_uuid(), 45.7058, -121.5135, 45.7066, -121.5120, 340, 'LOCAL', 0.85, NOW()),
(gen_random_uuid(), 45.7066, -121.5120, 45.7074, -121.5105, 360, 'LOCAL', 0.88, NOW()),
(gen_random_uuid(), 45.7074, -121.5105, 45.7082, -121.5090, 370, 'LOCAL', 0.90, NOW()),
(gen_random_uuid(), 45.7082, -121.5090, 45.7090, -121.5075, 380, 'LOCAL', 0.92, NOW()),

-- I-84 corridors and major arterials (Low-moderate scenic 0.35-0.55)
(gen_random_uuid(), 45.5600, -122.5050, 45.5608, -122.5035, 300, 'ARTERIAL', 0.38, NOW()),
(gen_random_uuid(), 45.5608, -122.5035, 45.5616, -122.5020, 320, 'ARTERIAL', 0.41, NOW()),
(gen_random_uuid(), 45.5616, -122.5020, 45.5624, -122.5005, 340, 'ARTERIAL', 0.44, NOW()),
(gen_random_uuid(), 45.5624, -122.5005, 45.5632, -122.4990, 350, 'ARTERIAL', 0.48, NOW()),
(gen_random_uuid(), 45.5632, -122.4990, 45.5640, -122.4975, 360, 'ARTERIAL', 0.52, NOW()),

-- I-5 north corridors (Low scenic 0.30-0.40)
(gen_random_uuid(), 45.6200, -122.6800, 45.6208, -122.6785, 300, 'ARTERIAL', 0.32, NOW()),
(gen_random_uuid(), 45.6208, -122.6785, 45.6216, -122.6770, 320, 'ARTERIAL', 0.35, NOW()),
(gen_random_uuid(), 45.6216, -122.6770, 45.6224, -122.6755, 340, 'ARTERIAL', 0.37, NOW()),
(gen_random_uuid(), 45.6224, -122.6755, 45.6232, -122.6740, 350, 'ARTERIAL', 0.39, NOW()),

-- I-5 south corridors (Low scenic 0.32-0.42)
(gen_random_uuid(), 45.4150, -122.6850, 45.4158, -122.6835, 300, 'ARTERIAL', 0.34, NOW()),
(gen_random_uuid(), 45.4158, -122.6835, 45.4166, -122.6820, 320, 'ARTERIAL', 0.37, NOW()),
(gen_random_uuid(), 45.4166, -122.6820, 45.4174, -122.6805, 340, 'ARTERIAL', 0.39, NOW()),
(gen_random_uuid(), 45.4174, -122.6805, 45.4182, -122.6790, 350, 'ARTERIAL', 0.42, NOW()),

-- McLoughlin connector (Low-moderate scenic 0.38-0.52)
(gen_random_uuid(), 45.4400, -122.6050, 45.4408, -122.6035, 280, 'ARTERIAL', 0.41, NOW()),
(gen_random_uuid(), 45.4408, -122.6035, 45.4416, -122.6020, 300, 'ARTERIAL', 0.44, NOW()),
(gen_random_uuid(), 45.4416, -122.6020, 45.4424, -122.6005, 320, 'ARTERIAL', 0.47, NOW()),
(gen_random_uuid(), 45.4424, -122.6005, 45.4432, -122.5990, 330, 'ARTERIAL', 0.50, NOW()),
(gen_random_uuid(), 45.4432, -122.5990, 45.4440, -122.5975, 340, 'ARTERIAL', 0.52, NOW())

ON CONFLICT DO NOTHING;
