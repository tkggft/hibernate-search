/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.mapper.pojo.mapping.definition.programmatic;

import java.util.Arrays;
import java.util.Collection;

import org.hibernate.search.engine.backend.document.model.ObjectFieldStorage;

/**
 * @author Yoann Rodiere
 */
public interface PropertyIndexedEmbeddedMappingContext extends PropertyMappingContext {

	PropertyIndexedEmbeddedMappingContext prefix(String prefix);

	PropertyIndexedEmbeddedMappingContext storage(ObjectFieldStorage storage);

	PropertyIndexedEmbeddedMappingContext maxDepth(Integer depth);

	default PropertyIndexedEmbeddedMappingContext includePaths(String ... paths) {
		return includePaths( Arrays.asList( paths ) );
	}

	PropertyIndexedEmbeddedMappingContext includePaths(Collection<String> paths);

}