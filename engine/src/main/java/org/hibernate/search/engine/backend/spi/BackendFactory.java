/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.engine.backend.spi;

import org.hibernate.search.engine.cfg.ConfigurationPropertySource;
import org.hibernate.search.engine.common.spi.BuildContext;

/**
 * @author Yoann Rodiere
 */
public interface BackendFactory {

	Backend<?> create(String name, BuildContext context, ConfigurationPropertySource propertySource);

}