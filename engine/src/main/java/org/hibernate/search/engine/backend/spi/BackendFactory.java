/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.engine.backend.spi;

import org.hibernate.search.engine.cfg.ConfigurationPropertySource;

/**
 * @author Yoann Rodiere
 */
public interface BackendFactory {

	/**
	 * @param name The name of the backend.
	 * @param context The build context.
	 * @param propertySource A configuration property source, appropriately masked so that the backend
	 * doesn't need to care about Hibernate Search prefixes (hibernate.search.*, etc.). All the properties
	 * can be accessed at the root.
	 * <strong>CAUTION:</strong> the property key {@code type} is reserved for use by the engine.
	 * @return A backend.
	 */
	BackendImplementor<?> create(String name, BackendBuildContext context, ConfigurationPropertySource propertySource);

}
