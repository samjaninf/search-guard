/*
 * Copyright 2015-2017 floragunn GmbH
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */

package com.floragunn.searchguard.auth.internal;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.bouncycastle.crypto.generators.OpenBSDBCrypt;
import org.elasticsearch.ElasticsearchSecurityException;
import org.elasticsearch.common.settings.Settings;

import com.floragunn.searchguard.auth.AuthenticationBackend;
import com.floragunn.searchguard.auth.AuthorizationBackend;
import com.floragunn.searchguard.configuration.ConfigurationRepository;
import com.floragunn.searchguard.support.ConfigConstants;
import com.floragunn.searchguard.user.AuthCredentials;
import com.floragunn.searchguard.user.User;

public class InternalAuthenticationBackend implements AuthenticationBackend, AuthorizationBackend {

    private final ConfigurationRepository configurationRepository;

    public InternalAuthenticationBackend(final ConfigurationRepository configurationRepository) {
        super();
        this.configurationRepository = configurationRepository;
    }

    @Override
    public boolean exists(User user) {

        final Settings cfg = getConfigSettings();
        if (cfg == null) {
            return false;
        }
        
        String hashed = cfg.get(user.getName() + ".hash");

        if (hashed == null) {
            
            for(String username:cfg.names()) {
                String u = cfg.get(username + ".username");
                if(user.getName().equals(u)) {
                    hashed = cfg.get(username+ ".hash");
                    break;
                }
            }
            
            if(hashed == null) {
                return false;
            }
        }
        
        final List<String> roles = cfg.getAsList(user.getName() + ".roles", Collections.emptyList());
        
        if(roles != null) {
            user.addRoles(roles);
        }
        
        return true;
    }
    
    @Override
    public User authenticate(final AuthCredentials credentials) {
        
        final Settings cfg = getConfigSettings();
        if (cfg == null) {
            throw new ElasticsearchSecurityException("Internal authentication backend not configured. May be Search Guard is not initialized. See http://docs.search-guard.com/v6/sgadmin");

        }

        String hashed = cfg.get(credentials.getUsername() + ".hash");

        if (hashed == null) {
            
            for(String username:cfg.names()) {
                String u = cfg.get(username + ".username");
                if(credentials.getUsername().equals(u)) {
                    hashed = cfg.get(username+ ".hash");
                    break;
                }
            }
            
            if(hashed == null) {
                throw new ElasticsearchSecurityException(credentials.getUsername() + " not found");
            }
        }
        
        final byte[] password = credentials.getPassword();
        
        if(password == null || password.length == 0) {
            throw new ElasticsearchSecurityException("empty passwords not supported");
        }

        ByteBuffer wrap = ByteBuffer.wrap(password);
        CharBuffer buf = StandardCharsets.UTF_8.decode(wrap);
        char[] array = new char[buf.limit()];
        buf.get(array);
        
        Arrays.fill(password, (byte)0);
       
        try {
            if (OpenBSDBCrypt.checkPassword(hashed, array)) {
                final List<String> roles = cfg.getAsList(credentials.getUsername() + ".roles", Collections.emptyList());
                final Settings customAttributes = cfg.getAsSettings(credentials.getUsername() + ".attributes");

                if(customAttributes != null) {
                    for(String attributeName: customAttributes.names()) {
                        credentials.addAttribute("attr.internal."+attributeName, customAttributes.get(attributeName));
                    }
                }

                return new User(credentials.getUsername(), roles, credentials);
            } else {
                throw new ElasticsearchSecurityException("password does not match");
            }
        } finally {
            Arrays.fill(wrap.array(), (byte)0);
            Arrays.fill(buf.array(), '\0');
            Arrays.fill(array, '\0');
        }
    }

    @Override
    public String getType() {
        return "internal";
    }

    private Settings getConfigSettings() {
        return configurationRepository.getConfiguration(ConfigConstants.CONFIGNAME_INTERNAL_USERS, false);
    }

    @Override
    public void fillRoles(User user, AuthCredentials credentials) throws ElasticsearchSecurityException {
        final Settings cfg = getConfigSettings();
        if (cfg == null) {
            throw new ElasticsearchSecurityException("Internal authentication backend not configured. May be Search Guard is not initialized. See http://docs.search-guard.com/v6/sgadmin");

        }
        final List<String> roles = cfg.getAsList(credentials.getUsername() + ".roles", Collections.emptyList());
        if(roles != null && !roles.isEmpty() && user != null) {
            user.addRoles(roles);
        }
    }
}
