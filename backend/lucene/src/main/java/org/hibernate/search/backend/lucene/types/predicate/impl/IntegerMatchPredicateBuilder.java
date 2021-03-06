/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.backend.lucene.types.predicate.impl;

import org.apache.lucene.document.IntPoint;
import org.apache.lucene.search.Query;

import org.hibernate.search.backend.lucene.search.predicate.impl.AbstractMatchPredicateBuilder;
import org.hibernate.search.backend.lucene.search.predicate.impl.LuceneSearchPredicateContext;
import org.hibernate.search.backend.lucene.types.converter.impl.LuceneFieldConverter;

class IntegerMatchPredicateBuilder extends AbstractMatchPredicateBuilder<Integer, Integer> {

	IntegerMatchPredicateBuilder(String absoluteFieldPath, LuceneFieldConverter<?, Integer> converter) {
		super( absoluteFieldPath, converter );
	}

	@Override
	protected Query doBuild(LuceneSearchPredicateContext context) {
		return IntPoint.newExactQuery( absoluteFieldPath, value );
	}
}
