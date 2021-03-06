/* Copyright (c) 2014 Dr David H. Akehurst (itemis), All Rights Reserved
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 */
package com.sun.jna.platform.win32.COM.util;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Guid.IID;
import com.sun.jna.platform.win32.Guid.REFIID;
import com.sun.jna.platform.win32.OaIdl.DISPID;
import com.sun.jna.platform.win32.OaIdl.DISPIDByReference;
import com.sun.jna.platform.win32.OaIdl.EXCEPINFO;
import com.sun.jna.platform.win32.OleAuto.DISPPARAMS;
import com.sun.jna.platform.win32.Variant;
import com.sun.jna.platform.win32.Variant.VARIANT;
import com.sun.jna.platform.win32.Variant.VariantArg;
import com.sun.jna.platform.win32.WinDef.LCID;
import com.sun.jna.platform.win32.WinDef.UINT;
import com.sun.jna.platform.win32.WinDef.UINTByReference;
import com.sun.jna.platform.win32.WinDef.WORD;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT.HRESULT;
import com.sun.jna.platform.win32.COM.COMException;
import com.sun.jna.platform.win32.COM.COMUtils;
import com.sun.jna.platform.win32.COM.Dispatch;
import com.sun.jna.platform.win32.COM.DispatchListener;
import com.sun.jna.platform.win32.COM.IDispatch;
import com.sun.jna.platform.win32.COM.IDispatchCallback;
import com.sun.jna.platform.win32.COM.Unknown;
import com.sun.jna.platform.win32.COM.util.annotation.ComEventCallback;
import com.sun.jna.platform.win32.COM.util.annotation.ComInterface;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

public class CallbackProxy implements IDispatchCallback {

	public CallbackProxy(Factory factory, Class<?> comEventCallbackInterface,
			IComEventCallbackListener comEventCallbackListener) {
		this.factory = factory;
		this.comEventCallbackInterface = comEventCallbackInterface;
		this.comEventCallbackListener = comEventCallbackListener;
		this.listenedToRiid = this.createRIID(comEventCallbackInterface);
		this.dsipIdMap = this.createDispIdMap(comEventCallbackInterface);
		this.dispatchListener = new DispatchListener(this);
	}

	Factory factory;
	Class<?> comEventCallbackInterface;
	IComEventCallbackListener comEventCallbackListener;
	REFIID listenedToRiid;
	public DispatchListener dispatchListener;
	Map<DISPID, Method> dsipIdMap;

	REFIID createRIID(Class<?> comEventCallbackInterface) {
		ComInterface comInterfaceAnnotation = comEventCallbackInterface.getAnnotation(ComInterface.class);
		if (null == comInterfaceAnnotation) {
			throw new COMException(
					"advise: Interface must define a value for either iid via the ComInterface annotation");
		}
		String iidStr = comInterfaceAnnotation.iid();
		if (null == iidStr || iidStr.isEmpty()) {
			throw new COMException("ComInterface must define a value for iid");
		}
		return new REFIID(new IID(iidStr).getPointer());
	}

	Map<DISPID, Method> createDispIdMap(Class<?> comEventCallbackInterface) {
		Map<DISPID, Method> map = new HashMap<DISPID, Method>();

		for (Method meth : comEventCallbackInterface.getMethods()) {
			ComEventCallback annotation = meth.getAnnotation(ComEventCallback.class);
			if (null != annotation) {
				int dispId = annotation.dispid();
				if (-1 == dispId) {
					dispId = this.fetchDispIdFromName(annotation);
				}
				map.put(new DISPID(dispId), meth);
			}
		}

		return map;
	}

	int fetchDispIdFromName(ComEventCallback annotation) {
		// TODO
		return -1;
	}

	void invokeOnThread(final DISPID dispIdMember, final REFIID riid, LCID lcid, WORD wFlags,
            final DISPPARAMS.ByReference pDispParams) {

            final Method eventMethod;
            if (CallbackProxy.this.dsipIdMap.containsKey(dispIdMember)) {
                eventMethod = CallbackProxy.this.dsipIdMap.get(dispIdMember);
                if (eventMethod.getParameterTypes().length != pDispParams.cArgs.intValue()) {
                    CallbackProxy.this.comEventCallbackListener.errorReceivingCallbackEvent(
                            "Trying to invoke method " + eventMethod + " with " + pDispParams.cArgs.intValue() + " arguments",
                            null);
                    return;
                }
            } else {
                CallbackProxy.this.comEventCallbackListener.errorReceivingCallbackEvent(
                        "No method found with dispId = " + dispIdMember, null);
                return;
            }
            
            // Arguments are converted to the JAVA side and IDispatch Interfaces
            // are wrapped into an ProxyObject if so requested.
            //
            // Out-Parameter need to be specified as VARIANT, VARIANT args are
            // not converted, so COM memory allocation rules apply.
            final Class<?>[] params = eventMethod.getParameterTypes();
            List<Object> rjargs = new ArrayList<Object>();
            if (pDispParams.cArgs.intValue() > 0) {
                VariantArg vargs = pDispParams.rgvarg;
                vargs.setArraySize(pDispParams.cArgs.intValue());
                for ( int i = 0; i < vargs.variantArg.length; i++) {
                    Class targetClass = params[vargs.variantArg.length - 1 - i];
                    Variant.VARIANT varg = vargs.variantArg[i];
                    Object jarg = Convert.toJavaObject(varg, targetClass, factory, true);
                    rjargs.add(jarg);
                }
            }

            List<Object> margs = new ArrayList<Object>();
            try {
                // Reverse order from calling convention
                int lastParamIdx = eventMethod.getParameterTypes().length - 1;
                for (int i = lastParamIdx; i >= 0; i--) {
                    margs.add(rjargs.get(i));
                }
                eventMethod.invoke(comEventCallbackListener, margs.toArray());
            } catch (Exception e) {
                List<String> decodedClassNames = new ArrayList<String>(margs.size());
                for(Object o: margs) {
                    if(o == null) {
                        decodedClassNames.add("NULL");
                    } else {
                        decodedClassNames.add(o.getClass().getName());
                    }
                }
                CallbackProxy.this.comEventCallbackListener.errorReceivingCallbackEvent(
                        "Exception invoking method " + eventMethod + " supplied: " + decodedClassNames.toString(), e);
            }
        }

	@Override
	public Pointer getPointer() {
		return this.dispatchListener.getPointer();
	}

	// ------------------------ IDispatch ------------------------------
	@Override
	public HRESULT GetTypeInfoCount(UINTByReference pctinfo) {
		return new HRESULT(WinError.E_NOTIMPL);
	}

	@Override
	public HRESULT GetTypeInfo(UINT iTInfo, LCID lcid, PointerByReference ppTInfo) {
		return new HRESULT(WinError.E_NOTIMPL);
	}

	@Override
	public HRESULT GetIDsOfNames(REFIID riid, WString[] rgszNames, int cNames, LCID lcid,
			DISPIDByReference rgDispId) {
		return new HRESULT(WinError.E_NOTIMPL);
	}

	@Override
	public HRESULT Invoke(DISPID dispIdMember, REFIID riid, LCID lcid, WORD wFlags,
			DISPPARAMS.ByReference pDispParams, VARIANT.ByReference pVarResult, EXCEPINFO.ByReference pExcepInfo,
			IntByReference puArgErr) {

                assert COMUtils.comIsInitialized() : "Assumption about COM threading broken.";
                
                this.invokeOnThread(dispIdMember, riid, lcid, wFlags, pDispParams);

		return WinError.S_OK;
	}

	// ------------------------ IUnknown ------------------------------
	@Override
	public HRESULT QueryInterface(REFIID refid, PointerByReference ppvObject) {
		if (null == ppvObject) {
			return new HRESULT(WinError.E_POINTER);
		}

		if (refid.equals(this.listenedToRiid)) {
			ppvObject.setValue(this.getPointer());
			return WinError.S_OK;
		}

		if (new Guid.IID(refid.getPointer()).equals(Unknown.IID_IUNKNOWN)) {
			ppvObject.setValue(this.getPointer());
			return WinError.S_OK;
		}

		if (new Guid.IID(refid.getPointer()).equals(Dispatch.IID_IDISPATCH)) {
			ppvObject.setValue(this.getPointer());
			return WinError.S_OK;
		}

		return new HRESULT(WinError.E_NOINTERFACE);
	}

	public int AddRef() {
		return 0;
	}

	public int Release() {
		return 0;
	}

}
