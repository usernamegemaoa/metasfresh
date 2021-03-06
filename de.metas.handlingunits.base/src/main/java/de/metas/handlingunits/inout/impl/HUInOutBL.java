package de.metas.handlingunits.inout.impl;

/*
 * #%L
 * de.metas.handlingunits.base
 * %%
 * Copyright (C) 2015 metas GmbH
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

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.adempiere.ad.trx.api.ITrx;
import org.adempiere.bpartner.service.IBPartnerDAO;
import org.adempiere.model.IContextAware;
import org.adempiere.model.InterfaceWrapperHelper;
import org.adempiere.util.Check;
import org.adempiere.util.Services;
import org.compiere.model.I_C_UOM;
import org.compiere.model.I_M_InOut;
import org.compiere.model.I_M_Product;
import org.compiere.model.I_M_Warehouse;
import org.compiere.model.X_M_Transaction;
import org.slf4j.Logger;

import de.metas.adempiere.model.I_C_BPartner_Location;
import de.metas.flatrate.interfaces.I_C_BPartner;
import de.metas.handlingunits.IHUAssignmentBL;
import de.metas.handlingunits.IHUAssignmentDAO;
import de.metas.handlingunits.IHUContext;
import de.metas.handlingunits.IHUContextFactory;
import de.metas.handlingunits.IHandlingUnitsBL;
import de.metas.handlingunits.empties.impl.EmptiesInOutProducer;
import de.metas.handlingunits.inout.IHUInOutBL;
import de.metas.handlingunits.inout.IHUInOutDAO;
import de.metas.handlingunits.inout.IReturnsInOutProducer;
import de.metas.handlingunits.model.I_C_OrderLine;
import de.metas.handlingunits.model.I_M_HU;
import de.metas.handlingunits.model.I_M_HU_Assignment;
import de.metas.handlingunits.model.I_M_HU_PI;
import de.metas.handlingunits.model.I_M_HU_PI_Item_Product;
import de.metas.handlingunits.model.I_M_InOutLine;
import de.metas.inoutcandidate.spi.impl.HUPackingMaterialDocumentLineCandidate;
import de.metas.logging.LogManager;
import de.metas.materialtracking.IMaterialTrackingAttributeBL;
import de.metas.materialtracking.model.I_M_Material_Tracking;

public class HUInOutBL implements IHUInOutBL
{
	private static final transient Logger logger = LogManager.getLogger(HUInOutBL.class);

	@Override
	public void updatePackingMaterialInOutLine(final de.metas.inout.model.I_M_InOutLine inoutLine,
			final HUPackingMaterialDocumentLineCandidate candidate)
	{
		Check.assumeNotNull(inoutLine, "inoutLine not null");
		Check.assumeNotNull(candidate, "candidate not null");

		final I_M_InOutLine inoutLineHU = InterfaceWrapperHelper.create(inoutLine, I_M_InOutLine.class);

		final I_M_Product product = candidate.getM_Product();
		final int productId = product.getM_Product_ID();
		final I_C_UOM uom = candidate.getC_UOM();
		final BigDecimal qtyEntered = candidate.getQty();
		final BigDecimal qty = candidate.getQtyInStockingUOM();
		final I_M_Material_Tracking materialTracking = candidate.getM_MaterialTracking();

		inoutLineHU.setM_Material_Tracking(materialTracking); // task 07734
		inoutLineHU.setM_Product_ID(productId);
		inoutLineHU.setC_UOM_ID(uom.getC_UOM_ID());
		inoutLineHU.setQtyEntered(qtyEntered);
		inoutLineHU.setMovementQty(qty);
		inoutLineHU.setIsPackagingMaterial(true);

		// task 09502: we set the M_Material_Tracking_ID, so let's also update the ASI, to have it all consistent.
		final IMaterialTrackingAttributeBL materialTrackingAttributeBL = Services.get(IMaterialTrackingAttributeBL.class);
		materialTrackingAttributeBL.createOrUpdateMaterialTrackingASI(inoutLineHU, materialTracking);

		// NOTE: packing material lines shall have no order line set (07969). This will prevent generating ICs.
		inoutLineHU.setC_OrderLine(null);

		InterfaceWrapperHelper.save(inoutLineHU);
	}

	@Override
	public void recreatePackingMaterialLines(final I_M_InOut inout)
	{
		final HUShipmentPackingMaterialLinesBuilder packingMaterialLinesBuilder = createHUShipmentPackingMaterialLinesBuilder(inout);

		final boolean deleteExistingPackingLines = true; // delete existing packing material lines, if any
		packingMaterialLinesBuilder.setOverrideExistingPackingMaterialLines(deleteExistingPackingLines);
		packingMaterialLinesBuilder.build();
	}

	@Override
	public void createPackingMaterialLines(final I_M_InOut inout)
	{
		final HUShipmentPackingMaterialLinesBuilder packingMaterialLinesBuilder = createHUShipmentPackingMaterialLinesBuilder(inout);
		packingMaterialLinesBuilder.setOverrideExistingPackingMaterialLines(false);
		packingMaterialLinesBuilder.build();
	}

	@Override
	public final HUShipmentPackingMaterialLinesBuilder createHUShipmentPackingMaterialLinesBuilder(final I_M_InOut shipment)
	{
		final HUShipmentPackingMaterialLinesBuilder packingMaterialLinesBuilder = new HUShipmentPackingMaterialLinesBuilder();
		packingMaterialLinesBuilder.setM_InOut(shipment);
		return packingMaterialLinesBuilder;
	}

	@Override
	public IReturnsInOutProducer createEmptiesInOutProducer(final Properties ctx)
	{
		return new EmptiesInOutProducer(ctx);
	}

	@Override
	public I_M_HU_PI getTU_HU_PI(final I_M_InOutLine inoutLine)
	{
		//
		// Get TU PI to use
		final I_M_HU_PI_Item_Product piItemProduct;
		if (inoutLine.getM_HU_PI_Item_Product_ID() > 0)
		{
			piItemProduct = inoutLine.getM_HU_PI_Item_Product();
		}
		else
		{
			// fallback
			// FIXME: this is a nasty workaround
			// Ideally would by to have M_HU_PI_Item_Product in receipt line
			final I_C_OrderLine orderLine = InterfaceWrapperHelper.create(inoutLine.getC_OrderLine(), I_C_OrderLine.class);
			if (orderLine == null)
			{
				logger.warn("Cannot get orderline from inout line: {}", inoutLine);
				return null;
			}
			piItemProduct = orderLine.getM_HU_PI_Item_Product();
		}
		if (piItemProduct == null)
		{
			logger.warn("Cannot get PI Item Product from inout line: {}", inoutLine);
			return null;
		}

		final I_M_HU_PI tuPI = piItemProduct.getM_HU_PI_Item().getM_HU_PI_Version().getM_HU_PI();
		return tuPI;
	}

	@Override
	public void destroyHUs(final I_M_InOut inout)
	{
		Check.assumeNotNull(inout, "inout not null");

		// services
		final IHUInOutDAO huInOutDAO = Services.get(IHUInOutDAO.class);
		final IHandlingUnitsBL handlingUnitsBL = Services.get(IHandlingUnitsBL.class);
		final IHUContextFactory huContextFactory = Services.get(IHUContextFactory.class);

		//
		// Get inout's assigned HUs
		final List<I_M_HU> hus = huInOutDAO.retrieveHandlingUnits(inout);
		if (hus.isEmpty())
		{
			return;
		}

		// TODO: make sure HUs were not touched! i.e. nobody took out some quantity, split, joined etc

		//
		// Create and configure the huContext for destroying the HUs
		final IContextAware context = InterfaceWrapperHelper.getContextAware(inout);
		final IHUContext huContext = huContextFactory.createMutableHUContextForProcessing(context);
		// If we deal with a receipt, we shall collect (and move back to Gebinde lager), only those packing materials that we own.
		if (!inout.isSOTrx())
		{
			huContext.getHUPackingMaterialsCollector().setCollectIfOwnPackingMaterialsOnly(true);
		}

		//
		// Mark assigned HUs as destroyed
		handlingUnitsBL.markDestroyed(huContext, hus);
	}

	@Override
	public void updateEffectiveValues(final I_M_InOutLine shipmentLine)
	{
		// avoid a huge development mistake
		Check.assume(shipmentLine.getM_InOut().isSOTrx(), "{} is a shipment line and not a receipt line", shipmentLine);

		// Skip packing materials line
		if (shipmentLine.isPackagingMaterial())
		{
			return;
		}

		final BigDecimal qtyCU_Effective;
		final BigDecimal qtyTU_Effective;
		final I_M_HU_PI_Item_Product piItemProduct_Effective;
		if (shipmentLine.isManualPackingMaterial())
		{
			qtyCU_Effective = shipmentLine.getQtyEntered(); // keep it as it is
			qtyTU_Effective = shipmentLine.getQtyTU_Override();
			piItemProduct_Effective = shipmentLine.getM_HU_PI_Item_Product_Override();
		}
		else
		{
			qtyCU_Effective = shipmentLine.getQtyCU_Calculated();
			qtyTU_Effective = shipmentLine.getQtyTU_Calculated();
			piItemProduct_Effective = shipmentLine.getM_HU_PI_Item_Product_Calculated();
		}

		shipmentLine.setQtyEntered(qtyCU_Effective);
		shipmentLine.setQtyEnteredTU(qtyTU_Effective);
		shipmentLine.setM_HU_PI_Item_Product(piItemProduct_Effective);
	}

	@Override
	public IReturnsInOutProducer createQualityReturnsInOutProducer(final Properties ctx, final List<I_M_HU> hus)
	{
		return new QualityReturnsInOutProducer(ctx, hus);
	}

	@Override
	public de.metas.handlingunits.model.I_M_InOut createReturnInOutForHUs(final Properties ctx, final List<I_M_HU> hus, final I_M_Warehouse warehouse, final Timestamp movementDate)
	{

		// services
		final IHUAssignmentDAO huAssignmentDAO = Services.get(IHUAssignmentDAO.class);
		final Map<Integer, List<I_M_HU>> partnerstoHUs = new HashMap<>();

		// inoutline table id
		final int inOutLineTableId = InterfaceWrapperHelper.getTableId(I_M_InOutLine.class);

		for (final I_M_HU hu : hus)
		{
			final IContextAware ctxAware = InterfaceWrapperHelper.getContextAware(hu);

			final List<I_M_HU_Assignment> inOutLineHUAssignments = huAssignmentDAO.retrieveTableHUAssignments(ctxAware, inOutLineTableId, hu);

			// search for the bpartner (vendor) based on the hu assignments of the receipt
			for (final I_M_HU_Assignment assignment : inOutLineHUAssignments)
			{
				final I_M_InOutLine inOutLine = InterfaceWrapperHelper.create(ctxAware.getCtx(), assignment.getRecord_ID(), I_M_InOutLine.class, ITrx.TRXNAME_None);

				final org.compiere.model.I_M_InOut inOut = inOutLine.getM_InOut();

				final int bpartnerID = inOut.getC_BPartner_ID();

				List<I_M_HU> husForPartner = partnerstoHUs.get(bpartnerID);

				if (husForPartner == null)
				{
					husForPartner = new ArrayList<I_M_HU>();
					partnerstoHUs.put(bpartnerID, husForPartner);
				}
				
				husForPartner.add(hu);
			}
		}

		// there will be as many return inouts as there are partners

		Set<Integer> keySet = partnerstoHUs.keySet();

		I_M_InOut inOut = null;

		for (final int partnerId : keySet)
		{
			inOut = createInOutForPartnerAndHUs(ctx, partnerId, partnerstoHUs.get(partnerId), warehouse, movementDate);
		}

		// return the last inout that was created
		de.metas.handlingunits.model.I_M_InOut huInOut = InterfaceWrapperHelper.create(inOut, de.metas.handlingunits.model.I_M_InOut.class);

		Services.get(IHUAssignmentBL.class).setAssignedHandlingUnits(huInOut, hus, ITrx.TRXNAME_ThreadInherited);

		return huInOut;
	}

	/**
	 * Create vendor return producer, set the details and use it to create the vendor return inout.
	 * 
	 * @param partnerId
	 * @param hus
	 * @return
	 */
	private I_M_InOut createInOutForPartnerAndHUs(final Properties ctx, final int partnerId, List<I_M_HU> hus, final I_M_Warehouse warehouse, final Timestamp movementDate)
	{
		final IHUInOutBL huInOutBL = Services.get(IHUInOutBL.class);
		final IBPartnerDAO bpartnerDAO = Services.get(IBPartnerDAO.class);

		final I_C_BPartner partner = InterfaceWrapperHelper.create(ctx, partnerId, I_C_BPartner.class, ITrx.TRXNAME_None);
		final IReturnsInOutProducer producer = huInOutBL.createQualityReturnsInOutProducer(ctx, hus);
		producer.setC_BPartner(partner);

		final I_C_BPartner_Location shipToLocation = bpartnerDAO.retrieveShipToLocation(ctx, partnerId, ITrx.TRXNAME_None);
		producer.setC_BPartner_Location(shipToLocation);

		final String movementType = X_M_Transaction.MOVEMENTTYPE_VendorReturns;

		producer.setMovementType(movementType);
		producer.setM_Warehouse(warehouse);

		producer.setMovementDate(movementDate);

		//
		// Create Shipment document and return it
		final I_M_InOut inOut = producer.create();
		return inOut;
	}

}
