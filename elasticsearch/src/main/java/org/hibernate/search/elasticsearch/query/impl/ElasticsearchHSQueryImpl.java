/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.elasticsearch.query.impl;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TopDocs;
import org.hibernate.search.elasticsearch.ElasticsearchProjectionConstants;
import org.hibernate.search.elasticsearch.client.impl.URLEncodedString;
import org.hibernate.search.elasticsearch.filter.ElasticsearchFilter;
import org.hibernate.search.elasticsearch.impl.ElasticsearchIndexManager;
import org.hibernate.search.elasticsearch.impl.ElasticsearchQueryOptions;
import org.hibernate.search.elasticsearch.impl.ElasticsearchService;
import org.hibernate.search.elasticsearch.impl.JsonBuilder;
import org.hibernate.search.elasticsearch.impl.ToElasticsearch;
import org.hibernate.search.elasticsearch.logging.impl.Log;
import org.hibernate.search.elasticsearch.util.impl.Window;
import org.hibernate.search.elasticsearch.work.impl.ElasticsearchWork;
import org.hibernate.search.elasticsearch.work.impl.ExplainResult;
import org.hibernate.search.elasticsearch.work.impl.SearchResult;
import org.hibernate.search.elasticsearch.work.impl.builder.SearchWorkBuilder;
import org.hibernate.search.engine.impl.FilterDef;
import org.hibernate.search.engine.integration.impl.ExtendedSearchIntegrator;
import org.hibernate.search.engine.metadata.impl.FacetMetadata;
import org.hibernate.search.engine.spi.DocumentBuilderIndexedEntity;
import org.hibernate.search.engine.spi.EntityIndexBinding;
import org.hibernate.search.exception.AssertionFailure;
import org.hibernate.search.filter.impl.FullTextFilterImpl;
import org.hibernate.search.indexes.spi.IndexManager;
import org.hibernate.search.query.dsl.impl.DiscreteFacetRequest;
import org.hibernate.search.query.dsl.impl.FacetRange;
import org.hibernate.search.query.dsl.impl.RangeFacetRequest;
import org.hibernate.search.query.engine.impl.AbstractHSQuery;
import org.hibernate.search.query.engine.impl.FacetComparators;
import org.hibernate.search.query.engine.impl.FacetManagerImpl;
import org.hibernate.search.query.engine.impl.TimeoutManagerImpl;
import org.hibernate.search.query.engine.spi.DocumentExtractor;
import org.hibernate.search.query.engine.spi.EntityInfo;
import org.hibernate.search.query.engine.spi.HSQuery;
import org.hibernate.search.query.facet.Facet;
import org.hibernate.search.query.facet.FacetSortOrder;
import org.hibernate.search.query.facet.FacetingRequest;
import org.hibernate.search.spatial.DistanceSortField;
import org.hibernate.search.spi.CustomTypeMetadata;
import org.hibernate.search.spi.IndexedTypeMap;
import org.hibernate.search.spi.IndexedTypeSet;
import org.hibernate.search.util.impl.CollectionHelper;
import org.hibernate.search.util.impl.ReflectionHelper;
import org.hibernate.search.util.logging.impl.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Query implementation based on Elasticsearch.
 *
 * @author Gunnar Morling
 */
public class ElasticsearchHSQueryImpl extends AbstractHSQuery {

	private static final JsonParser JSON_PARSER = new JsonParser();

	private static final Log LOG = LoggerFactory.make( Log.class );

	/**
	 * ES default limit for (firstResult + maxResult)
	 */
	private static final int MAX_RESULT_WINDOW_SIZE = 10000;

	private static final Set<String> SUPPORTED_PROJECTION_CONSTANTS = Collections.unmodifiableSet(
			CollectionHelper.asSet(
					ElasticsearchProjectionConstants.ID,
					ElasticsearchProjectionConstants.OBJECT_CLASS,
					ElasticsearchProjectionConstants.SCORE,
					ElasticsearchProjectionConstants.SOURCE,
					ElasticsearchProjectionConstants.SPATIAL_DISTANCE,
					ElasticsearchProjectionConstants.THIS,
					ElasticsearchProjectionConstants.TOOK,
					ElasticsearchProjectionConstants.TIMED_OUT
			)
	);

	/**
	 * The constructor-provided payload for the Search API, holding
	 * the search query in particular.
	 * <p>
	 * This raw payload will serve as a basis for the actual payload to be sent toElasticsearch,
	 * which will also contain automatically generated data related in particular to sorts
	 * and projections.
	 */
	private final JsonObject rawSearchPayload;

	private Integer resultSize;
	private IndexSearcher searcher;
	private SearchResult searchResult;

	private transient FacetManagerImpl facetManager;

	public ElasticsearchHSQueryImpl(JsonObject rawSearchPayload, ExtendedSearchIntegrator extendedIntegrator,
			IndexedTypeSet types) {
		super( extendedIntegrator, types );
		this.rawSearchPayload = rawSearchPayload;
	}

	public ElasticsearchHSQueryImpl(JsonObject rawSearchPayload, ExtendedSearchIntegrator extendedIntegrator,
			IndexedTypeMap<CustomTypeMetadata> types) {
		super( extendedIntegrator, types );
		this.rawSearchPayload = rawSearchPayload;
	}

	@Override
	public HSQuery luceneQuery(Query query) {
		throw LOG.hsQueryLuceneQueryUnsupported();
	}

	@Override
	public FacetManagerImpl getFacetManager() {
		if ( facetManager == null ) {
			facetManager = new FacetManagerImpl( this );
		}
		return facetManager;
	}

	@Override
	public Query getLuceneQuery() {
		throw LOG.hsQueryLuceneQueryUnsupported();
	}

	@Override
	public String getQueryString() {
		return rawSearchPayload.toString();
	}

	@Override
	public DocumentExtractor queryDocumentExtractor() {
		IndexSearcher searcher = getOrCreateSearcher();
		if ( searcher != null ) {
			return new ElasticsearchScrollAPIDocumentExtractor( searcher );
		}
		else {
			return EmptyDocumentExtractor.get();
		}
	}

	SearchResult getSearchResult() {
		if ( searchResult == null ) {
			execute();
		}
		return searchResult;
	}

	@Override
	public int queryResultSize() {
		if ( searchResult == null ) {
			execute();
		}
		return resultSize;
	}

	@Override
	public Explanation explain(int documentId) {
		if ( searchResult == null ) {
			execute();
		}

		JsonObject hit = searchResult.getHits()
				// TODO Is it right to use the document id that way? I am not quite clear about its semantics
				.get( documentId )
				.getAsJsonObject();

		/*
		 * Do not add every property of the original payload: some properties, such as "_source", do not have the same syntax
		 * and are not necessary to the explanation.
		 */
		JsonObject explainPayload = JsonBuilder.object()
				.add( "query", searcher.payload.get( "query" ) )
				.build();

		ElasticsearchWork<ExplainResult> work = searcher.elasticsearchService.getWorkFactory().explain(
				URLEncodedString.fromJSon( hit.get( "_index" ) ),
				URLEncodedString.fromJSon( hit.get( "_type" ) ),
				URLEncodedString.fromJSon( hit.get( "_id" ) ),
				explainPayload )
				.build();

		ExplainResult result = searcher.elasticsearchService.getWorkProcessor().executeSyncUnsafe( work );
		JsonObject explanation = result.getJsonObject().get( "explanation" ).getAsJsonObject();

		return convertExplanation( explanation );
	}

	private Explanation convertExplanation(JsonObject explanation) {
		float value = explanation.get( "value" ).getAsFloat();
		String description = explanation.get( "description" ).getAsString();
		JsonElement explanationDetails = explanation.get( "details" );

		List<Explanation> details;

		if ( explanationDetails != null ) {
			details = new ArrayList<>( explanationDetails.getAsJsonArray().size() );

			for ( JsonElement detail : explanationDetails.getAsJsonArray() ) {
				details.add( convertExplanation( detail.getAsJsonObject() ) );
			}
		}
		else {
			details = Collections.emptyList();
		}

		return Explanation.match( value, description, details );
	}

	@Override
	protected void clearCachedResults() {
		searcher = null;
		searchResult = null;
		resultSize = null;
	}

	@Override
	protected TimeoutManagerImpl buildTimeoutManager() {
		return new TimeoutManagerImpl(
				rawSearchPayload,
				timeoutExceptionFactory,
				this.extendedIntegrator.getTimingSource()
		);
	}

	@Override
	public List<EntityInfo> queryEntityInfos() {
		if ( searchResult == null ) {
			execute();
		}

		JsonArray hits = searchResult.getHits();
		List<EntityInfo> results = new ArrayList<>( hits.size() );

		for ( JsonElement hit : hits ) {
			EntityInfo entityInfo = searcher.convertQueryHit( searchResult, hit.getAsJsonObject() );
			if ( entityInfo != null ) {
				results.add( entityInfo );
			}
		}

		return results;
	}

	@Override
	protected Set<String> getSupportedProjectionConstants() {
		return SUPPORTED_PROJECTION_CONSTANTS;
	}

	@Override
	protected Set<IndexManager> getIndexManagers(EntityIndexBinding binding) {
		Set<IndexManager> indexManagers = super.getIndexManagers( binding );
		for ( IndexManager indexManager : indexManagers ) {
			if ( !( indexManager instanceof ElasticsearchIndexManager ) ) {
				throw LOG.cannotRunEsQueryTargetingEntityIndexedWithNonEsIndexManager(
					binding.getDocumentBuilder().getTypeIdentifier(),
					rawSearchPayload.toString()
				);
			}
		}
		return indexManagers;
	}

	private void execute() {
		IndexSearcher searcher = getOrCreateSearcher();
		if ( searcher != null ) {
			searchResult = searcher.search();
		}
		else {
			searchResult = EmptySearchResult.get();
		}
		resultSize = searchResult.getTotalHitCount();
	}

	private IndexSearcher getOrCreateSearcher() {
		if ( searcher != null ) {
			return searcher;
		}

		ElasticsearchService elasticsearchService = null;
		Map<String, EntityIndexBinding> targetedEntityBindingsByName = buildTargetedEntityIndexBindingsByName();
		Set<URLEncodedString> indexNames = new HashSet<>();

		for ( Map.Entry<String, EntityIndexBinding> entry: targetedEntityBindingsByName.entrySet() ) {
			EntityIndexBinding binding = entry.getValue();

			Set<IndexManager> indexManagers = getIndexManagers( binding );

			for ( IndexManager indexManager : indexManagers ) {
				ElasticsearchIndexManager esIndexManager = (ElasticsearchIndexManager) indexManager;
				indexNames.add( URLEncodedString.fromString( esIndexManager.getActualIndexName() ) );
				if ( elasticsearchService == null ) {
					elasticsearchService = esIndexManager.getElasticsearchService();
				}
				else if ( elasticsearchService != esIndexManager.getElasticsearchService() ) {
					throw new AssertionFailure( "Found two index managers refering to two different ElasticsearchService instances" );
				}
			}
		}

		if ( indexNames.isEmpty() ) {
			/*
			 * In this case we cannot send a request to Elasticsearch,
			 * because by default it will query all indexes.
			 */
			return null;
		}

		this.searcher = new IndexSearcher( elasticsearchService, targetedEntityBindingsByName, indexNames );
		return searcher;
	}

	/**
	 * Stores all information required to execute the query: query string, relevant indices, query parameters, ...
	 */
	private class IndexSearcher {
		private final ElasticsearchService elasticsearchService;
		private final Set<URLEncodedString> indexNames;

		private final ElasticsearchQueryOptions queryOptions;
		private final QueryHitConverter queryHitConverter;
		private final Map<FacetingRequest, FacetMetadata> facetingRequestsAndMetadata;
		private final JsonObject payload;

		private IndexSearcher(ElasticsearchService elasticsearchService,
				Map<String, EntityIndexBinding> targetedEntityBindingsByName, Set<URLEncodedString> indexNames) {
			this.elasticsearchService = elasticsearchService;
			this.indexNames = indexNames;

			JsonArray typeFilters = new JsonArray();
			for ( String typeName : targetedEntityBindingsByName.keySet() ) {
				typeFilters.add( getEntityTypeFilter( typeName ) );
			}

			this.queryOptions = elasticsearchService.getQueryOptions();

			// Query filters; always a type filter, possibly a tenant id filter;
			JsonObject filteredQuery = getFilteredQuery( rawSearchPayload.get( "query" ), typeFilters );

			JsonBuilder.Object payloadBuilder = JsonBuilder.object();
			payloadBuilder.add( "query", filteredQuery );

			/*
			 * Initialize the sortByDistanceIndex to detect if the results are sorted
			 * by distance and the position
			 */
			Integer sortByDistanceIndex = getSortByDistanceIndex();

			this.queryHitConverter = QueryHitConverter.builder( elasticsearchService.getQueryFactory(), targetedEntityBindingsByName )
					.setProjectedFields( projectedFields )
					.setSortByDistance( sortByDistanceIndex, spatialSearchCenter, spatialFieldName )
					.build();
			queryHitConverter.contributeToPayload( payloadBuilder );

			Collection<FacetingRequest> facetingRequests = getFacetManager().getFacetRequests().values();
			if ( !facetingRequests.isEmpty() ) {
				JsonBuilder.Object facets = JsonBuilder.object();

				facetingRequestsAndMetadata =
						buildFacetingRequestsAndMetadata( facetingRequests, targetedEntityBindingsByName.values() );
				for ( Entry<FacetingRequest, FacetMetadata> facetingRequestEntry : facetingRequestsAndMetadata.entrySet() ) {
					addFacetingRequest( facets, facetingRequestEntry.getKey(), facetingRequestEntry.getValue() );
				}

				payloadBuilder.add( "aggregations", facets );
			}
			else {
				facetingRequestsAndMetadata = Collections.emptyMap();
			}

			// TODO: HSEARCH-2254 embedded fields (see https://github.com/searchbox-io/Jest/issues/304)
			if ( sort != null ) {
				validateSortFields( targetedEntityBindingsByName.values() );
				payloadBuilder.add( "sort", ToElasticsearch.fromLuceneSort( sort ) );
			}

			this.payload = payloadBuilder.build();
		}

		private JsonObject getFilteredQuery(JsonElement originalQuery, JsonArray typeFilters) {
			JsonArray filters = new JsonArray();

			JsonObject tenantFilter = getTenantIdFilter();
			if ( tenantFilter != null ) {
				filters.add( tenantFilter );
			}

			// wrap type filters into should if there is more than one
			filters.add( ToElasticsearch.condition( "should", typeFilters ) );

			// facet filters
			for ( Query query : getFacetManager().getFacetFilters().getFilterQueries() ) {
				filters.add( ToElasticsearch.fromLuceneQuery( query ) );
			}

			// user filter
			if ( userFilter != null ) {
				filters.add( ToElasticsearch.fromLuceneQuery( userFilter ) );
			}

			if ( !filterDefinitions.isEmpty() ) {
				for ( FullTextFilterImpl fullTextFilter : filterDefinitions.values() ) {
					JsonObject filter = buildFullTextFilter( fullTextFilter );
					if ( filter != null ) {
						filters.add( filter );
					}
				}
			}

			JsonBuilder.Object boolBuilder = JsonBuilder.object();

			if ( originalQuery != null && !originalQuery.isJsonNull() ) {
				boolBuilder.add( "must", originalQuery );
			}

			if ( filters.size() == 1 ) {
				boolBuilder.add( "filter", filters.get( 0 ) );
			}
			else {
				boolBuilder.add( "filter", filters );
			}

			return JsonBuilder.object().add( "bool", boolBuilder.build() ).build();
		}

		private JsonObject getEntityTypeFilter(String name) {
			JsonObject value = new JsonObject();
			value.addProperty( "value", name );

			JsonObject type = new JsonObject();
			type.add( "type", value );

			return type;
		}

		private JsonObject getTenantIdFilter() {
			if ( tenantId == null ) {
				return null;
			}

			JsonObject value = new JsonObject();
			value.addProperty( DocumentBuilderIndexedEntity.TENANT_ID_FIELDNAME, tenantId );

			JsonObject tenantFilter = new JsonObject();
			tenantFilter.add( "term", value );

			return tenantFilter;
		}

		private void addFacetingRequest(JsonBuilder.Object facets, FacetingRequest facetingRequest, FacetMetadata facetMetadata) {
			String sourceFieldAbsoluteName = facetMetadata.getSourceField().getAbsoluteName();
			String facetSubfieldName = facetMetadata.getPath().getRelativeName();

			ToElasticsearch.addFacetingRequest( facets, facetingRequest, sourceFieldAbsoluteName, facetSubfieldName );
		}

		/**
		 * Returns the index of the DistanceSortField in the Sort array.
		 *
		 * @return the index, null if no DistanceSortField has been found
		 */
		private Integer getSortByDistanceIndex() {
			int i = 0;
			if ( sort != null ) {
				for ( SortField sortField : sort.getSort() ) {
					if ( sortField instanceof DistanceSortField ) {
						return i;
					}
					i++;
				}
			}
			return null;
		}

		SearchResult search() {
			return search( false );
		}

		SearchResult searchWithScrollEnabled() {
			return search( true );
		}

		private SearchResult search(boolean enableScrolling) {
			SearchWorkBuilder builder = elasticsearchService.getWorkFactory()
					.search( payload ).indexes( indexNames );

			if ( enableScrolling ) {
				/*
				 * Note: "firstResult" is currently being ignored by Elasticsearch when scrolling.
				 * See https://github.com/elastic/elasticsearch/issues/9373
				 * To work this around, we don't use the "from" parameter here, and the document
				 * extractor will skip the results by scrolling until it gets the right index.
				 */
				builder.scrolling(
						/*
						 * The "size" parameter has a slightly different meaning when scrolling: it defines the window
						 * size for the first search *and* for the next calls to the scroll API.
						 * We still reduce the number of results in accordance to "maxResults", but that's done on our side
						 * in the document extractor.
						 */
						getQueryOptions().getScrollFetchSize(),
						getQueryOptions().getScrollTimeout()
				);
			}
			else {
				builder.paging(
						firstResult,
						// If the user has given a 'size' value, take it as is, let ES itself complain if it's too high; if no value is
						// given, I take as much as possible, as by default only 10 rows would be returned
						maxResults != null ? maxResults : MAX_RESULT_WINDOW_SIZE - firstResult
				);
			}

			ElasticsearchWork<SearchResult> work = builder.build();
			return elasticsearchService.getWorkProcessor().executeSyncUnsafe( work );
		}

		private ElasticsearchQueryOptions getQueryOptions() {
			return queryOptions;
		}

		/**
		 * Scroll through search results, using a previously obtained scrollId.
		 */
		SearchResult scroll(String scrollId) {
			ElasticsearchQueryOptions queryOptions = getQueryOptions();
			ElasticsearchWork<SearchResult> work = elasticsearchService.getWorkFactory()
					.scroll( scrollId, queryOptions.getScrollTimeout() ).build();
			return elasticsearchService.getWorkProcessor().executeSyncUnsafe( work );
		}

		void clearScroll(String scrollId) {
			ElasticsearchWork<?> work = elasticsearchService.getWorkFactory()
					.clearScroll( scrollId ).build();
			elasticsearchService.getWorkProcessor().executeSyncUnsafe( work );
		}

		EntityInfo convertQueryHit(SearchResult searchResult, JsonObject hit) {
			return this.queryHitConverter.convert( searchResult, hit );
		}

	}

	@Override
	protected void extractFacetResults() {
		SearchResult searchResult = getSearchResult();
		JsonObject aggregations = searchResult.getAggregations();
		if ( aggregations == null ) {
			return;
		}

		Map<String, List<Facet>> results = new HashMap<>();
		for ( Map.Entry<FacetingRequest, FacetMetadata> entry : searcher.facetingRequestsAndMetadata.entrySet() ) {
			FacetingRequest facetRequest = entry.getKey();
			FacetMetadata facetMetadata = entry.getValue();
			List<Facet> facets;
			if ( facetRequest instanceof DiscreteFacetRequest ) {
				facets = extractDiscreteFacets( aggregations, (DiscreteFacetRequest) facetRequest, facetMetadata );
				// Discrete facets are sorted by Elasticsearch
			}
			else {
				facets = extractRangeFacets( aggregations, (RangeFacetRequest<?>) facetRequest, facetMetadata );
				if ( !FacetSortOrder.RANGE_DEFINITION_ORDER.equals( facetRequest.getSort() ) ) {
					Collections.sort( facets, FacetComparators.get( facetRequest.getSort() ) );
				}
			}

			results.put( facetRequest.getFacetingName(), facets );
		}
		getFacetManager().setFacetResults( results );
	}

	private List<Facet> extractRangeFacets(JsonObject aggregations, RangeFacetRequest<?> facetRequest,
			FacetMetadata facetMetadata) {
		if ( !ReflectionHelper.isIntegerType( facetRequest.getFacetValueType() )
				&& !Date.class.isAssignableFrom( facetRequest.getFacetValueType() )
				&& !ReflectionHelper.isFloatingPointType( facetRequest.getFacetValueType() ) ) {
			throw LOG.unsupportedFacetRangeParameter( facetRequest.getFacetValueType().getName() );
		}

		ArrayList<Facet> facets = new ArrayList<>();
		for ( FacetRange<?> facetRange : facetRequest.getFacetRangeList() ) {
			JsonElement aggregation = aggregations.get( facetRequest.getFacetingName() + "-" + facetRange.getIdentifier() );
			if ( aggregation == null ) {
				continue;
			}
			int docCount = aggregation.getAsJsonObject().get( "doc_count" ).getAsInt();
			if ( docCount == 0 && !facetRequest.hasZeroCountsIncluded() ) {
				continue;
			}
			facets.add( facetRequest.createFacet( facetMetadata, facetRange.getRangeString(), docCount ) );
		}
		return facets;
	}

	private List<Facet> extractDiscreteFacets(JsonObject aggregations, DiscreteFacetRequest facetRequest,
			FacetMetadata facetMetadata) {
		JsonElement aggregation = aggregations.get( facetRequest.getFacetingName() );
		if ( aggregation == null ) {
			return Collections.emptyList();
		}

		// deal with nested aggregation for nested documents
		if ( isNested( facetRequest ) ) {
			aggregation = aggregation.getAsJsonObject().get( facetRequest.getFacetingName() );
		}
		if ( aggregation == null ) {
			return Collections.emptyList();
		}

		ArrayList<Facet> facets = new ArrayList<>();
		for ( JsonElement bucket : aggregation.getAsJsonObject().get( "buckets" ).getAsJsonArray() ) {
			facets.add( facetRequest.createFacet(
					facetMetadata,
					bucket.getAsJsonObject().get( "key" ).getAsString(),
					bucket.getAsJsonObject().get( "doc_count" ).getAsInt() ) );
		}
		return facets;
	}

	private JsonObject buildFullTextFilter(FullTextFilterImpl fullTextFilter) {

		/*
		 * FilterKey implementations and Filter(Factory) do not have to be threadsafe wrt their parameter injection
		 * as FilterCachingStrategy ensure a memory barrier between concurrent thread calls
		 */
		FilterDef def = extendedIntegrator.getFilterDefinition( fullTextFilter.getName() );
		//def can never be null, it's guarded by enableFullTextFilter(String)

		if ( isPreQueryFilterOnly( def ) ) {
			return null;
		}

		Object filterOrFactory = createFilterInstance( fullTextFilter, def );
		return createFullTextFilter( def, filterOrFactory );
	}

	protected JsonObject createFullTextFilter(FilterDef def, Object filterOrFactory) {
		JsonObject jsonFilter;
		if ( def.getFactoryMethod() != null ) {
			try {
				Object candidateFilter = def.getFactoryMethod().invoke( filterOrFactory );
				jsonFilter = toJsonFilter( candidateFilter );
				if ( jsonFilter == null ) {
					throw LOG.filterFactoryMethodReturnsUnsupportedType( def.getImpl().getName(), def.getFactoryMethod().getName() );
				}
			}
			catch (IllegalAccessException | InvocationTargetException e) {
				throw LOG.filterFactoryMethodInaccessible( def.getImpl().getName(), def.getFactoryMethod().getName(), e );
			}
		}
		else {
			jsonFilter = toJsonFilter( filterOrFactory );
			if ( jsonFilter == null ) {
				throw LOG.filterHasUnsupportedType( filterOrFactory == null ? null : filterOrFactory.getClass().getName() );
			}
		}

		return jsonFilter;
	}

	private JsonObject toJsonFilter(Object candidateFilter) {
		if ( candidateFilter instanceof Query ) {
			// This also handles the case where the query extends Filter
			return ToElasticsearch.fromLuceneQuery( (Query) candidateFilter );
		}
		else if ( candidateFilter instanceof ElasticsearchFilter ) {
			return JSON_PARSER.parse( ( (ElasticsearchFilter) candidateFilter ).getJsonFilter() ).getAsJsonObject();
		}
		else {
			return null;
		}
	}

	private boolean isNested(DiscreteFacetRequest facetRequest) {
		//TODO HSEARCH-2097 Drive through meta-data
//		return FieldHelper.isEmbeddedField( facetRequest.getFieldName() );
		return false;
	}

	private class ElasticsearchScrollAPIDocumentExtractor implements DocumentExtractor {

		// Search parameters
		private final IndexSearcher searcher;
		private final Integer queryIndexLimit;

		// Position
		private String scrollId;

		// Results
		private Integer totalResultCount;
		private final Window<EntityInfo> results;

		private ElasticsearchScrollAPIDocumentExtractor(IndexSearcher searcher) {
			this.searcher = searcher;
			queryIndexLimit = ElasticsearchHSQueryImpl.this.maxResults == null
					? null : ElasticsearchHSQueryImpl.this.firstResult + ElasticsearchHSQueryImpl.this.maxResults;
			ElasticsearchQueryOptions queryOptions = searcher.getQueryOptions();
			results = new Window<>(
					/*
					 * The offset is currently ignored by Elasticsearch.
					 * See https://github.com/elastic/elasticsearch/issues/9373
					 * To work this around, we don't use the "from" parameter when querying, and the document
					 * extractor will skip the results by querying until it gets the right index.
					 */
					0,
					/*
					 * Sizing for the worst-case scenario: we just fetched a batch of elements to
					 * give access to the result just after the previously fetched results, and
					 * we still need to keep enough of the previous elements to backtrack.
					 */
					queryOptions.getScrollBacktrackingWindowSize() + queryOptions.getScrollFetchSize()
					);
		}

		@Override
		public EntityInfo extract(int index) throws IOException {
			if ( index < 0 ) {
				throw new IndexOutOfBoundsException( "Index must be >= 0" );
			}
			else if ( index < results.start() ) {
				throw LOG.backtrackingWindowOverflow( searcher.getQueryOptions().getScrollBacktrackingWindowSize(), results.start(), index );
			}

			if ( totalResultCount == null ) {
				initResults();
			}

			int maxIndex = getMaxIndex();
			if ( maxIndex < index ) {
				throw new IndexOutOfBoundsException( "Index must be <= " + maxIndex );
			}

			boolean fetchMayReturnResults = true;
			while ( results.start() + results.size() <= index && fetchMayReturnResults ) {
				fetchMayReturnResults = fetchNextResults();
			}

			return results.get( index );
		}

		@Override
		public int getFirstIndex() {
			return ElasticsearchHSQueryImpl.this.firstResult;
		}

		@Override
		public int getMaxIndex() {
			if ( totalResultCount == null ) {
				initResults();
			}

			if ( queryIndexLimit == null ) {
				return totalResultCount - 1;
			}
			else {
				return Math.min( totalResultCount, queryIndexLimit ) - 1;
			}
		}

		@Override
		public void close() {
			if ( scrollId != null ) {
				searcher.clearScroll( scrollId );
				scrollId = null;
				totalResultCount = null;
				results.clear();
			}
		}

		@Override
		public TopDocs getTopDocs() {
			throw LOG.documentExtractorTopDocsUnsupported();
		}

		private void initResults() {
			SearchResult searchResult = searcher.searchWithScrollEnabled();
			totalResultCount = searchResult.getTotalHitCount();
			extractWindow( searchResult );
		}

		/**
		 * @return {@code true} if at least one result was fetched, {@code false} otherwise.
		 */
		private boolean fetchNextResults() {
			if ( totalResultCount <= results.start() + results.size() ) {
				// No more results to fetch
				return false;
			}

			SearchResult searchResult = searcher.scroll( scrollId );
			return extractWindow( searchResult );
		}

		/**
		 * @return {@code true} if at least one result was fetched, {@code false} otherwise.
		 */
		private boolean extractWindow(SearchResult searchResult) {
			boolean fetchedAtLeastOne = false;
			scrollId = searchResult.getScrollId();
			JsonArray hits = searchResult.getHits();
			for ( JsonElement hit : hits ) {
				EntityInfo converted = searcher.convertQueryHit( searchResult, hit.getAsJsonObject() );
				if ( converted != null ) {
					results.add( converted );
					fetchedAtLeastOne = true;
				}
			}
			return fetchedAtLeastOne;
		}
	}
}