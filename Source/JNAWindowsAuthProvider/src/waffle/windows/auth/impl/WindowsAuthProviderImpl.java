/*******************************************************************************
* Waffle (http://waffle.codeplex.com)
* 
* Copyright (c) 2010 Application Security, Inc.
* 
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Eclipse Public License v1.0
* which accompanies this distribution, and is available at
* http://www.eclipse.org/legal/epl-v10.html
*
* Contributors:
*     Application Security, Inc.
*******************************************************************************/
package waffle.windows.auth.impl;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import waffle.windows.auth.IWindowsAccount;
import waffle.windows.auth.IWindowsAuthProvider;
import waffle.windows.auth.IWindowsComputer;
import waffle.windows.auth.IWindowsCredentialsHandle;
import waffle.windows.auth.IWindowsDomain;
import waffle.windows.auth.IWindowsIdentity;
import waffle.windows.auth.IWindowsSecurityContext;

import com.google.common.collect.MapMaker;
import com.sun.jna.NativeLong;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Netapi32Util;
import com.sun.jna.platform.win32.Netapi32Util.DomainTrust;
import com.sun.jna.platform.win32.Secur32;
import com.sun.jna.platform.win32.Sspi;
import com.sun.jna.platform.win32.Sspi.CtxtHandle;
import com.sun.jna.platform.win32.Sspi.SecBufferDesc;
import com.sun.jna.platform.win32.W32Errors;
import com.sun.jna.platform.win32.Win32Exception;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT.HANDLEByReference;
import com.sun.jna.ptr.NativeLongByReference;

/**
 * Windows Auth Provider.
 * @author dblock[at]dblock[dot]org
 */
public class WindowsAuthProviderImpl implements IWindowsAuthProvider {
	
	ConcurrentMap<String, CtxtHandle> _continueContexts = null;
	
	public WindowsAuthProviderImpl() {
		this(30);
	}
	
	/**
	 * A Windows authentication provider.
	 * @param continueContextsTimeout
	 *  Timeout for security contexts in seconds.
	 */
	public WindowsAuthProviderImpl(int continueContextsTimeout) {
		_continueContexts = new MapMaker()
			.expiration(continueContextsTimeout, TimeUnit.SECONDS)
			.makeMap();
	}

	public IWindowsSecurityContext acceptSecurityToken(String connectionId, byte[] token, String securityPackage) {

		if (token == null || token.length == 0) {
        	_continueContexts.remove(connectionId);
            throw new Win32Exception(W32Errors.SEC_E_INVALID_TOKEN);			
		}
		
        IWindowsCredentialsHandle serverCredential = new WindowsCredentialsHandleImpl(
                null, Sspi.SECPKG_CRED_INBOUND, securityPackage);
        serverCredential.initialize();

        SecBufferDesc pbServerToken = new SecBufferDesc(Sspi.SECBUFFER_TOKEN, Sspi.MAX_TOKEN_SIZE);
        SecBufferDesc pbClientToken = new SecBufferDesc(Sspi.SECBUFFER_TOKEN, token);
    	NativeLongByReference pfClientContextAttr = new NativeLongByReference();
    	
    	CtxtHandle continueContext = _continueContexts.get(connectionId);
    	
    	CtxtHandle phNewServerContext = new CtxtHandle();
    	int rc = Secur32.INSTANCE.AcceptSecurityContext(serverCredential.getHandle(), 
    			continueContext, pbClientToken, new NativeLong(Sspi.ISC_REQ_CONNECTION), 
    			new NativeLong(Sspi.SECURITY_NATIVE_DREP), phNewServerContext, 
    			pbServerToken, pfClientContextAttr, null);

    	WindowsSecurityContextImpl sc = new WindowsSecurityContextImpl();
    	sc.setCredentialsHandle(serverCredential.getHandle());
    	sc.setSecurityPackage(securityPackage);
    	sc.setSecurityContext(phNewServerContext);

    	switch (rc)
        {
            case W32Errors.SEC_E_OK:
        		// the security context received from the client was accepted
            	_continueContexts.remove(connectionId);
            	//  if an output token was generated by the function, it must be sent to the client process
            	if (pbServerToken != null 
            			&& pbServerToken.pBuffers != null
            			&& pbServerToken.cBuffers.intValue() == 1 
            			&& pbServerToken.pBuffers[0].cbBuffer.intValue() > 0) {
            		sc.setToken(pbServerToken.getBytes());
            	}
            	sc.setContinue(false);
            	break;
            case W32Errors.SEC_I_CONTINUE_NEEDED:
            	// the server must send the output token to the client and wait for a returned token
            	_continueContexts.put(connectionId, phNewServerContext);
            	sc.setToken(pbServerToken.getBytes());
            	sc.setContinue(true);
            	break;
        	default:
        		sc.dispose();
        		WindowsSecurityContextImpl.dispose(continueContext);
            	_continueContexts.remove(connectionId);
                throw new Win32Exception(rc);
        }
    	
    	return sc;
	}

	public IWindowsComputer getCurrentComputer() {
		try {
			return new WindowsComputerImpl(InetAddress.getLocalHost().getHostName());
		} catch (UnknownHostException e) {
			throw new RuntimeException(e);
		}
	}

	public IWindowsDomain[] getDomains() {
		List<IWindowsDomain> domains = new ArrayList<IWindowsDomain>();
		DomainTrust[] trusts = Netapi32Util.getDomainTrusts();
		for(DomainTrust trust : trusts) {
			domains.add(new WindowsDomainImpl(trust));
		}
		return domains.toArray(new IWindowsDomain[0]);
	}

	public IWindowsIdentity logonDomainUser(String username, String domain, String password) {
		return logonDomainUserEx(username, domain, password,
				WinBase.LOGON32_LOGON_NETWORK, WinBase.LOGON32_PROVIDER_DEFAULT);
	}

	public IWindowsIdentity logonDomainUserEx(String username, String domain,
			String password, int logonType, int logonProvider) {
		HANDLEByReference phUser = new HANDLEByReference();
		if (! Advapi32.INSTANCE.LogonUser(username, domain, password, 
				logonType, logonProvider, phUser)) {
			throw new Win32Exception(Kernel32.INSTANCE.GetLastError());
		}
		return new WindowsIdentityImpl(phUser.getValue());
	}

	public IWindowsIdentity logonUser(String username, String password) {		
        // username@domain UPN format is natively supported by the 
		// Windows LogonUser API process domain\\username format
        String domain = null;
        String[] userNameDomain = username.split("\\\\", 2);
        if (userNameDomain.length == 2) {
            username = userNameDomain[1];
            domain = userNameDomain[0];
        }
        return logonDomainUser(username, domain, password);
	}

	public IWindowsAccount lookupAccount(String username) {
		return new WindowsAccountImpl(username);
	}

	public void resetSecurityToken(String connectionId) {
    	_continueContexts.remove(connectionId);
	}
	
	/**
	 * Number of elements in the continue contexts map.
	 * @return
	 *  Number of elements in the hash map.
	 */
	public int getContinueContextsSize() {
		return _continueContexts.size();
	}
}
