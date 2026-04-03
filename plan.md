# MoodRide Implementation Plan

## Problem Statement
Build a production-ready scenic route generation platform that maximizes scenic quality over circular driving loops. This is a full-stack distributed system involving geospatial processing, NP-hard optimization, async job queues, multi-layer caching, and real-time delivery.

## Approach & Strategy

### Development Philosophy
- **AI-Assisted, Human-Guided**: Leverage AI for code generation, let humans make architectural decisions
- **Incremental Delivery**: Build vertically through the stack (backend → API → frontend) phase by phase
- **Test as You Build**: Verify each phase before moving to the next
- **Cost-Conscious AI Usage**: Use right-sized models for different tasks

### AI Model Strategy (Cost vs Quality)

**For this project, I recommend a tiered approach:**

1. **Planning & Architecture** (Sonnet 4.5 - Current Model)
    - Complex system design decisions
    - Algorithm implementation (beam search, graph algorithms)
    - Performance optimization
    - Cost: ~$3 per million input tokens, ~$15 per million output tokens

2. **Routine Implementation** (Haiku 4.5 or GPT-4.1)
    - CRUD endpoints
    - Database schema migrations
    - Configuration files
    - Simple business logic
    - Cost: Haiku ~$0.25-$1 per million tokens (60-80% cheaper)

3. **Code Review & Debugging** (Sonnet 4.5)
    - Critical path debugging
    - Performance issues
    - Complex bug fixes

**Recommended Workflow:**
- Start each phase with Sonnet for design
- Use `task` tool with Haiku for implementation tasks
- Return to Sonnet for integration and debugging

**Estimated Total Cost for Full Build:**
- With all Sonnet: $150-250
- With tiered approach: $60-100
- **Savings: ~60%**

### Tech Stack (From Spec)

**Backend:**
- PostgreSQL 15+ with PostGIS extension
- Kafka for async job queue
- Redis for multi-layer caching
- Debezium for CDC

**Frontend:**
- Next.js 14+ (React)
- Mapbox GL JS for mapping
- WebSocket client for real-time updates

**Infrastructure (Local Dev):**
- Docker Compose for all services
- PostgreSQL + PostGIS
- Redis
- Kafka + Zookeeper
- Debezium Connect

**External APIs:**
- OpenStreetMap (OSM) - road network data
- NLCD - land use classification
- OpenTopoData - elevation data (self-hosted)
- Natural Earth - water bodies
- TomTom API - traffic (optional for MVP)

## Implementation Phases

### Phase 0: Project Setup & Infrastructure (Current)
**Status:** Not started  
**Estimated Time:** 2-3 days

- [ ] Initialize Spring Boot project structure
- [ ] Set up Docker Compose for local infrastructure
- [ ] PostgreSQL + PostGIS container
- [ ] Redis container
- [ ] Kafka + Zookeeper containers
- [ ] Create basic project structure (service layer, repository, config)
- [ ] Verify all services can communicate

**Verification:** `docker-compose up` brings up all services healthy

---

### Phase 1: OSM Ingestion & PostGIS Foundation
**Status:** Not started  
**Estimated Time:** 1-2 weeks  
**Dependencies:** Phase 0

**Scope:**
- PostgreSQL schema for `road_segment` and initial tables
- OSM PBF parser (using `osm4j` library)
- Road segment extraction with classification
- Geometry processing and curvature calculation
- H3 index assignment
- PostGIS GIST indexes
- Initial data: Portland, OR metro area (~500K segments)

**Success Criteria:**
- Spatial query `ST_DWithin` returns ~50K segments in < 500ms
- All road types properly classified
- H3 indexes assigned correctly
- GIST indexes verified with `EXPLAIN ANALYZE`

---

### Phase 2: Scenic Scoring Pipeline
**Status:** Not started  
**Estimated Time:** 1-2 weeks  
**Dependencies:** Phase 1

**Scope:**
- Integrate 6 data sources:
    - NLCD land use data
    - OpenTopoData elevation (Docker)
    - Natural Earth water bodies
    - OSM POI extraction
    - Road curvature (from Phase 1)
    - Traffic (TomTom - optional for v1)
- Implement scenic scoring algorithm with vibe weights
- H3 tile-level aggregation
- `scenic_score_tile` table population
- Batch pipeline as Spring Boot CLI app

**Success Criteria:**
- Top-scoring tiles (Columbia River Gorge, Forest Park) have scores > 0.85
- All 6 signals computed correctly
- Tiles cached and queryable
- Pipeline completes Portland area in < 2 hours

---

### Phase 3: PostGIS Integration & Road Graph
**Status:** Not started  
**Estimated Time:** 1 week  
**Dependencies:** Phase 1, Phase 2

**Scope:**
- Spatial subgraph extraction from PostGIS
- In-memory graph construction (JGraphT)
- Scenic score enrichment on edges
- Redis caching for segment metadata
- Query optimization for 50km radius extractions

**Success Criteria:**
- 30km subgraph extraction: 10K-20K segments in < 500ms
- Graph construction: < 300ms
- Edges have correct scenic weights

---

### Phase 4: Route Generation Worker
**Status:** Not started  
**Estimated Time:** 1-2 weeks  
**Dependencies:** Phase 3

**Scope:**
- Beam search algorithm (K=10, time-budget pruning)
- Circular loop enforcement (A* return leg)
- Route quality scoring
- Scenic highlight extraction
- Kafka consumer worker
- Unit tests for all time budgets (30/60/90/120 min)
- Integration tests

**Success Criteria:**
- Routes are valid loops (start ≈ end within 500m)
- Duration within ±10% of budget
- Scenic score > 60 for Portland "coastal" vibe
- Generation time: 2-5 seconds

---

### Phase 5: Async Job Queue & API
**Status:** Not started  
**Estimated Time:** 1 week  
**Dependencies:** Phase 4

**Scope:**
- REST API endpoints:
    - `POST /routes` - submit job
    - `GET /routes/{jobId}` - poll status
    - `GET /routes/{routeId}` - fetch route
    - `GET /scenic-regions` - regional data
- Job state machine (PENDING → IN_PROGRESS → COMPLETED/FAILED)
- Kafka job publishing
- WebSocket notification service
- Timeout watchdog
- Retry logic

**Success Criteria:**
- Submit route request → get jobId immediately
- Route completes within 10 seconds
- WebSocket notification fires < 200ms after completion
- Failed jobs retry correctly

---

### Phase 6: Multi-Layer Caching
**Status:** Not started  
**Estimated Time:** 1 week  
**Dependencies:** Phase 5

**Scope:**
- 4-layer Redis cache:
    - Scenic tile cache (pre-warmed)
    - Route result cache
    - Segment metadata cache
    - Regional popularity cache
- Read-through cache strategy
- TTL configuration
- Cache warming scripts
- Hit ratio monitoring (Micrometer)

**Success Criteria:**
- Scenic tile cache hit rate > 90%
- Cached tile lookup < 10ms
- Cached route retrieval < 20ms
- Metrics properly emitted to Prometheus

---

### Phase 7: Frontend Application
**Status:** Not started  
**Estimated Time:** 1-2 weeks  
**Dependencies:** Phase 5

**Scope:**
- Next.js app with Mapbox GL JS
- Location picker (geolocation + manual)
- Time budget slider
- Vibe multi-select (max 3)
- Route request flow
- WebSocket integration
- Route rendering with color gradient
- Scenic highlights overlay
- Rating system
- Mobile-responsive design
- Polling fallback

**Success Criteria:**
- Full user flow works end-to-end
- Mobile and desktop responsive
- WebSocket delivers real-time updates
- Map renders routes smoothly
- No UI blocking during route generation

---

### Phase 8: CDC Invalidation Pipeline
**Status:** Not started  
**Estimated Time:** 1 week  
**Dependencies:** Phase 6

**Scope:**
- Debezium connector for PostgreSQL
- CDC consumer service
- Redis cache invalidation on WAL events
- Tile recomputation triggers
- Monitoring: consumer lag, invalidation rate

**Success Criteria:**
- Tile update in Postgres → Redis key invalidated < 5 seconds
- CDC consumer lag < 100 messages
- No stale cache reads after updates

---

## Key Technical Challenges

1. **Beam Search Optimization**: NP-hard problem requires careful algorithm tuning
2. **Geospatial Query Performance**: PostGIS indexes critical for sub-second queries
3. **Cache Consistency**: CDC pipeline must maintain data freshness
4. **External API Orchestration**: 6 data sources with different rate limits
5. **Real-Time Delivery**: WebSocket reliability with polling fallback

## Cost Considerations

**External APIs:**
- OSM: Free (rate-limited)
- NLCD: Free (public data)
- OpenTopoData: Self-hosted (free)
- Natural Earth: Free (public data)
- TomTom: 2,500 free calls/day (defer to v2 if needed)

**Infrastructure (Local Dev):**
- All Docker containers: Free
- Mapbox: Free tier (50K loads/month)

**AI Development:**
- Tiered model strategy: ~$60-100 for full build
- Can reduce by doing more manual coding

## Next Steps

1. **Immediate**: Set up project structure and Docker infrastructure (Phase 0)
2. **Day 1-2**: PostgreSQL schema + OSM parser (Phase 1 start)
3. **Week 1**: Complete data ingestion pipeline
4. **Week 2**: Scenic scoring implementation
5. **Week 3-4**: Route generation core algorithm
6. **Week 5-6**: API and job queue
7. **Week 7-8**: Frontend
8. **Week 9**: CDC and polish

**Total Estimated Timeline**: 8-10 weeks for full production system

## Questions for User

1. **Development Environment**: Windows (current), WSL, or Docker for all services?
2. **Data Scope**: Start with Portland, OR as specified, or different region?
3. **External APIs**: Skip TomTom traffic for v1 to avoid API costs?
4. **Frontend Framework**: Confirm Next.js or prefer different framework?
5. **Deployment Target**: Plan to deploy (AWS/GCP/local) or just local development?
