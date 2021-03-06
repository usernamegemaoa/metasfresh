package de.metas.handlingunits.inout.impl;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.adempiere.ad.trx.api.ITrx;
import org.adempiere.model.InterfaceWrapperHelper;
import org.adempiere.util.Check;
import org.adempiere.util.Services;
import org.adempiere.util.lang.IReference;
import org.compiere.model.I_C_UOM;
import org.compiere.model.I_M_InOut;
import org.compiere.model.I_M_InOutLine;
import org.compiere.model.I_M_Product;
import org.compiere.util.Util;
import org.compiere.util.Util.ArrayKey;

import de.metas.handlingunits.IHUAssignmentBL;
import de.metas.handlingunits.IHandlingUnitsBL;
import de.metas.handlingunits.inout.IQualityReturnsInOutLinesBuilder;
import de.metas.handlingunits.model.I_M_HU;
import de.metas.handlingunits.model.X_M_HU;
import de.metas.handlingunits.storage.IHUProductStorage;
import de.metas.inout.IInOutBL;
import de.metas.product.IProductBL;

/*
 * #%L
 * de.metas.handlingunits.base
 * %%
 * Copyright (C) 2017 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

/**
 * Builder for vendor return inout lines that are for non-packing material products
 * 
 * @author metas-dev <dev@metasfresh.com>
 *
 */
public class QualityReturnsInOutLinesBuilder implements IQualityReturnsInOutLinesBuilder
{

	// services
	private final transient IHandlingUnitsBL handlingUnitsBL = Services.get(IHandlingUnitsBL.class);

	// referenced inout header
	private final IReference<I_M_InOut> _inoutRef;

	/**
	 * Map on product keys and their inout lines
	 */
	private final Map<ArrayKey, I_M_InOutLine> _inOutLines = new HashMap<>();

	public static QualityReturnsInOutLinesBuilder newBuilder(final IReference<I_M_InOut> inoutRef)
	{
		return new QualityReturnsInOutLinesBuilder(inoutRef);
	}

	private QualityReturnsInOutLinesBuilder(final IReference<I_M_InOut> inoutRef)
	{
		super();

		Check.assumeNotNull(inoutRef, "inoutRef not null");
		_inoutRef = inoutRef;
	}

	private final I_M_InOut getM_InOut()
	{
		return _inoutRef.getValue();
	}

	@Override
	public QualityReturnsInOutLinesBuilder addHUProductStorage(final IHUProductStorage productStorage)
	{
		final I_M_InOut inout = getM_InOut();
		InterfaceWrapperHelper.save(inout); // make sure inout header is saved

		updateInOutLine(productStorage);

		return this;
	}

	/**
	 * Extract the product from the product storage together with its quantity.
	 * If there is already an inout line for this product, update the existing qty based on the new one. If the inout line for this product does not exist yet, create it.
	 * In the end assign the handling unit to the inout line and mark the HU as shipped.
	 * 
	 * @param productStorage
	 */
	private void updateInOutLine(final IHUProductStorage productStorage)
	{
		// services
		final IProductBL productBL = Services.get(IProductBL.class);
		final IHUAssignmentBL huAssignmentBL = Services.get(IHUAssignmentBL.class);

		// Skip it if product storage is empty
		if (productStorage.isEmpty())
		{
			return;
		}

		final I_M_Product product = productStorage.getM_Product();

		final I_M_InOutLine inOutLine = getCreateInOutLine(product);

		final I_C_UOM productUOM = productBL.getStockingUOM(product);
		final BigDecimal qtyToMove = productStorage.getQty(productUOM);

		//
		// Adjust movement line's qty to move
		final BigDecimal inOutLine_Qty_Old = inOutLine.getMovementQty();
		final BigDecimal inOutLine_Qty_New = inOutLine_Qty_Old.add(qtyToMove);
		inOutLine.setMovementQty(inOutLine_Qty_New);

		//
		// Also set the qty entered
		inOutLine.setQtyEntered(inOutLine_Qty_New);

		// Make sure the inout line is saved
		InterfaceWrapperHelper.save(inOutLine);

		// Assign the HU to the inout line and mark it as shipped
		{
			final I_M_HU hu = productStorage.getM_HU();
			final String trxName = ITrx.TRXNAME_ThreadInherited;

			final I_M_HU huTopLevel = handlingUnitsBL.getTopLevelParent(hu);
			final I_M_HU luHU = handlingUnitsBL.getLoadingUnitHU(hu);
			final I_M_HU tuHU = handlingUnitsBL.getTransportUnitHU(hu);

			huAssignmentBL.createTradingUnitDerivedAssignmentBuilder(InterfaceWrapperHelper.getCtx(hu), inOutLine, huTopLevel, luHU, tuHU, trxName)
					.build();

			// mark hu as shipped
			hu.setHUStatus(X_M_HU.HUSTATUS_Shipped);

			InterfaceWrapperHelper.save(hu);
		}
	}

	/**
	 * Search the inout lines map (_inOutLines) and check if there is already a line for the given product. In case it already exists, return it. Otherwise, create a line for this product.
	 * 
	 * @param product
	 * @return
	 */
	private I_M_InOutLine getCreateInOutLine(final I_M_Product product)
	{

		// services
		final IInOutBL inOutBL = Services.get(IInOutBL.class);

		final I_M_InOut inout = _inoutRef.getValue();

		if (inout.getM_InOut_ID() <= 0)
		{
			// nothing created
			return null;
		}

		//
		// Check if we already have a movement line for our key
		final ArrayKey inOutLineKey = mkInOutLineKey(product);
		final I_M_InOutLine existingInOutLine = _inOutLines.get(inOutLineKey);

		// return the existing inout line if found
		if (existingInOutLine != null)
		{
			return existingInOutLine;
		}

		//
		// Create a new inOut line for the product

		final I_M_InOutLine newInOutLine = inOutBL.newInOutLine(inout, I_M_InOutLine.class);
		newInOutLine.setAD_Org_ID(inout.getAD_Org_ID());
		newInOutLine.setM_InOut_ID(inout.getM_InOut_ID());

		newInOutLine.setM_Product(product);

		// NOTE: we are not saving the inOut line

		//
		// Add the movement line to our map (to not created it again)
		_inOutLines.put(inOutLineKey, newInOutLine);

		return newInOutLine;
	}

	/**
	 * Make a unique key for the given product. This will serve in mapping the inout lines to their products.
	 * 
	 * @param product
	 * @return
	 */
	private ArrayKey mkInOutLineKey(final I_M_Product product)
	{
		return Util.mkKey(product.getM_Product_ID());
	}

	@Override
	public boolean isEmpty()
	{
		return _inOutLines.isEmpty();
	}

}
