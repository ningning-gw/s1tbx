/*
 * Copyright (C) 2010 Brockmann Consult GmbH (info@brockmann-consult.de)
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */
package org.esa.beam.framework.datamodel;

import com.bc.ceres.core.ServiceRegistry;
import com.bc.ceres.core.ServiceRegistryManager;

import org.esa.beam.BeamCoreActivator;
import org.esa.beam.util.Guardian;

import java.util.Set;

/**
 * Created by Marco Peters.
 *
 * @author Marco Peters
 * @version $Revision$ $Date$
 */
public class PointingFactoryRegistry {

    private static ServiceRegistry<PointingFactory> typeToFactoryMap;

    private PointingFactoryRegistry() {
        ServiceRegistryManager serviceRegistryManager = ServiceRegistryManager.getInstance();
        typeToFactoryMap = serviceRegistryManager.getServiceRegistry(PointingFactory.class);
        if (!BeamCoreActivator.isStarted()) {
            BeamCoreActivator.loadServices(typeToFactoryMap);
        }
    }

    public static PointingFactoryRegistry getInstance() {
        return Holder.instance;
    }

    public PointingFactory getPointingFactory(String productType) {
        Guardian.assertNotNullOrEmpty("productType", productType);
        Set<PointingFactory> services = typeToFactoryMap.getServices();
        for (PointingFactory descriptor : services) {
            String[] supportedProductTypes = descriptor.getSupportedProductTypes();
            for (String supportedType : supportedProductTypes) {
                if (productType.equalsIgnoreCase(supportedType)) {
                    return descriptor;
                }
            }
        }
        return null;
    }

    public void addFactory(PointingFactory pointingFactory) {
            typeToFactoryMap.addService(pointingFactory);
    }
    
    // Initialization on demand holder idiom
    private static class Holder {
        private static final PointingFactoryRegistry instance = new PointingFactoryRegistry();
    }
}
