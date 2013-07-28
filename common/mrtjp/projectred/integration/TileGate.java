package mrtjp.projectred.integration;

import static codechicken.lib.vec.Rotation.sideRotations;
import static codechicken.lib.vec.Vector3.center;

import java.util.Arrays;

import mrtjp.projectred.ProjectRed;
import mrtjp.projectred.interfaces.wiring.IBundledEmitter;
import mrtjp.projectred.interfaces.wiring.IBundledUpdatable;
import mrtjp.projectred.interfaces.wiring.IBundledWire;
import mrtjp.projectred.interfaces.wiring.IConnectable;
import mrtjp.projectred.interfaces.wiring.IRedstoneWire;
import mrtjp.projectred.interfaces.wiring.IWire;
import mrtjp.projectred.multipart.wiring.gates.GateLogic;
import mrtjp.projectred.multipart.wiring.gates.GateLogic.WorldStateBound;
import mrtjp.projectred.utils.BasicUtils;
import mrtjp.projectred.utils.BasicWireUtils;
import mrtjp.projectred.utils.Coords;
import mrtjp.projectred.utils.Dir;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.ForgeDirection;
import codechicken.lib.data.MCDataInput;
import codechicken.lib.data.MCDataOutput;
import codechicken.lib.lighting.LazyLightMatrix;
import codechicken.lib.raytracer.IndexedCuboid6;
import codechicken.lib.vec.Cuboid6;
import codechicken.lib.vec.Rotation;
import codechicken.lib.vec.Vector3;
import codechicken.multipart.IFaceRedstonePart;
import codechicken.multipart.JCuboidPart;
import codechicken.multipart.JNormalOcclusion;
import codechicken.multipart.NormalOcclusionTest;
import codechicken.multipart.RedstoneInteractions;
import codechicken.multipart.TFacePart;
import codechicken.multipart.TMultiPart;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

public class TileGate extends JCuboidPart implements TFacePart, IFaceRedstonePart, JNormalOcclusion, IConnectable, IBundledUpdatable, IBundledEmitter {
	private EnumGate type;

	/** Server-side logic for gate **/
	private GateLogic logic;

	/**
	 * The inside face for the containing block that its sitting on. (0 for
	 * sitting on top of a block.)
	 **/
	private byte side;

	/** The ForgeDirection that the front of the gate is facing. **/
	private byte front;

	private boolean requiresTickUpdate;
	private GateLogic.WithPointer pointer;
	private boolean hasBundledConnections;

	float pointerPos; // rendering only
	float pointerSpeed; // rendering only

	private int gateSettings;
	private short[] inputs = new short[4];
	private short[] outputs = new short[4];
	private int renderState;
	private int prevRenderState;
	private boolean updatePending;

	@Deprecated
	private short[] absOutputs = new short[6];
	@Deprecated
	private short[] prevAbsOutputs = new short[6];
	@Deprecated
	private short[] prevOutputs = new short[4];

	private boolean isFirstTick = true;

	public TileGate(EnumGate type) {
		if (type == null) {
			throw new IllegalArgumentException("type cannot be null");
		}
		this.type = type;
		this.front = (byte) front;
		createLogic();
	}

	public void setupPlacement(EntityPlayer p, int side) {
		this.side = (byte) (side ^ 1);
		front = (byte) ((side + 2) % 6);
		Vec3 look = p.getLook(1.0f);
		double absx = Math.abs(look.xCoord);
		double absy = Math.abs(look.yCoord);
		double absz = Math.abs(look.zCoord);
		switch (side) {
		case Dir.PX:
		case Dir.NX:
			if (absy > absz)
				front = (byte) (look.yCoord > 0 ? Dir.PY : Dir.NY);
			else
				front = (byte) (look.zCoord > 0 ? Dir.PZ : Dir.NZ);
			break;
		case Dir.PY:
		case Dir.NY:
			if (absx > absz)
				front = (byte) (look.xCoord > 0 ? Dir.PX : Dir.NX);
			else
				front = (byte) (look.zCoord > 0 ? Dir.PZ : Dir.NZ);
			break;
		case Dir.PZ:
		case Dir.NZ:
			if (absy > absx)
				front = (byte) (look.yCoord > 0 ? Dir.PY : Dir.NY);
			else
				front = (byte) (look.xCoord > 0 ? Dir.PX : Dir.NX);
			break;
		}
	}

	public int getSide() {
		return side;
	}

	public int getFront() {
		return front;
	}

	public EnumGate getGateType() {
		return type;
	}

	public GateLogic getLogic() {
		return logic;
	}

	public int getRenderState() {
		return prevRenderState;
	}

	@Override
	public void writeDesc(MCDataOutput packet) {
		if (type == null) {
			return;
		}
		// TODO change this to raw in/out
		NBTTagCompound p = new NBTTagCompound();
		p.setByte("t", (byte) (type.ordinal() | 0));
		p.setByte("s", side);
		p.setByte("f", front);
		p.setShort("r", (short) prevRenderState);
		if (pointer != null) {
			p.setShort("p", (short) pointer.getPointerPosition());
			p.setFloat("P", pointer.getPointerSpeed());
		}
		packet.writeNBTTagCompound(p);
	}

	@Override
	public void readDesc(MCDataInput packet) {
		// TODO change this to raw in/out
		NBTTagCompound nbt = packet.readNBTTagCompound();
		type = EnumGate.VALUES[nbt.getByte("t") & 0x7F];
		side = nbt.getByte("s");
		front = nbt.getByte("f");
		prevRenderState = nbt.getShort("r") & 0xFFFFF;
		if (nbt.hasKey("p")) {
			pointerPos = nbt.getShort("p");
			pointerSpeed = nbt.getFloat("P");
		}
	}

	@Override
	public void save(NBTTagCompound tag) {
		super.save(tag);

		tag.setByte("type", type == null ? -1 : (byte) type.ordinal());
		tag.setByte("side", side);
		tag.setByte("front", front);

		tag.setBoolean("version2", true);

		tag.setLong("outputs", toBitfield16(outputs));
		tag.setLong("inputs", toBitfield16(inputs));
		tag.setLong("prevOutputs", toBitfield16(prevOutputs));

		tag.setShort("renderState", (short) renderState);
		tag.setShort("prevRenderState", (short) prevRenderState);
		tag.setBoolean("updatePending", updatePending);
		tag.setShort("gateSettings", (short) gateSettings);
		if (logic != null && requiresTickUpdate) {
			NBTTagCompound tag2 = new NBTTagCompound();
			logic.write(tag2);
			tag.setTag("logic", tag2);
		}
	}

	@Override
	public void load(NBTTagCompound tag) {
		super.load(tag);
		try {
			type = EnumGate.VALUES[tag.getByte("type")];
		} catch (Exception e) {
			type = EnumGate.AND; // shouldn't happen
		}
		side = tag.getByte("side");
		front = tag.getByte("front");

		renderState = tag.getShort("renderState") & 0xFFFF;
		prevRenderState = tag.getShort("prevRenderState") & 0xFFFF;

		updatePending = tag.getBoolean("updatePending");
		gateSettings = tag.getShort("gateSettings") & 0xFFFF;

		if (tag.getTag("inputs") instanceof NBTTagLong) {
			if (tag.getBoolean("version2")) {
				fromBitfield16(tag.getLong("inputs"), inputs);
				fromBitfield16(tag.getLong("outputs"), outputs);
				fromBitfield16(tag.getLong("prevOutputs"), prevOutputs);

				for (int k = 0; k < 4; k++) {
					absOutputs[relToAbsDirection(k)] = outputs[k];
					prevAbsOutputs[relToAbsDirection(k)] = prevOutputs[k];
				}

			} else {
				fromBitfield8(tag.getLong("inputs"), inputs);
				fromBitfield8(tag.getLong("outputs"), outputs);
				fromBitfield8(tag.getLong("absOutputs"), absOutputs);
				fromBitfield8(tag.getLong("prevAbsOutputs"), prevAbsOutputs);
				fromBitfield8(tag.getLong("prevOutputs"), prevOutputs);
			}
		}

		createLogic();

		if (logic != null && tag.hasKey("logic")) {
			logic.read(tag.getCompoundTag("logic"));
		}
	}

	@Override
	public void update() {
		if (BasicUtils.isServer(world())) {
			if (requiresTickUpdate) {
				updateLogic(true, false);
				if (logic instanceof WorldStateBound) {
					if (((WorldStateBound) logic).needsWorldInfo()) {
						((WorldStateBound) logic).setWorldInfo(world(), x(), y(), z());
					}
				}
			} else if (isFirstTick) {
				updateLogic(false, false);
				isFirstTick = false;
			}

		} else {
			pointerPos += pointerSpeed;
		}
	}

	public short[] computeInputs() {
		short[] newInputs = new short[4];
		int relativeRotationIndex = Rotation.rotationTo(side, front);
		for (int i = 0; i < 4; i++) {
			newInputs[i] = (short) RedstoneInteractions.getPowerTo(this, Rotation.rotateSide(side, relativeRotationIndex));
			relativeRotationIndex++;
			if (relativeRotationIndex > 3) {
				relativeRotationIndex = 0;
			}
		}
		return newInputs;
	}

	public void updateLogic(boolean fromTick, boolean forceUpdate) {
		if (type == null) {
			return;
		}

		inputs = computeInputs();
		// update render state with new inputs but not new outputs
		updateRenderState();

		if (forceUpdate || fromTick == requiresTickUpdate) {
			short[] oldOutputs = outputs.clone();
			logic.computeOutFromIn(inputs, outputs, gateSettings);
			if (forceUpdate || !Arrays.equals(outputs, oldOutputs)) {
				if (!updatePending) {
					updateChange();
					updatePending = true;
				}
			}
		}
	}

	private void updateRenderState() {
		renderState = logic.getRenderState(inputs, outputs, gateSettings);
		if (prevRenderState != renderState) {
			prevRenderState = renderState;
			updateChange();
		}
	}

	public void updateChange() {
		tile().markDirty();
		tile().notifyPartChange();
		sendDescUpdate();
	}

	@Override
	public void onNeighborChanged() {
		checkSupport();
		updateLogic(false, false);
	}

	@Override
	public void onPartChanged() {
		checkSupport();
		if (BasicUtils.isClient(world()))
			return;
		updateLogic(false, false);
	}

	/**
	 * See if the gate is still attached to something.
	 */
	public void checkSupport() {
		if (BasicUtils.isClient(world())) {
			return;
		}
		Coords localCoord = new Coords(x(), y(), z());
		localCoord.orientation = ForgeDirection.getOrientation(this.getSide());
		localCoord.moveForwards(1);
		Block supporter = Block.blocksList[world().getBlockId(localCoord.x, localCoord.y, localCoord.z)];
		if (!BasicWireUtils.canPlaceWireOnSide(world(), localCoord.x, localCoord.y, localCoord.z, localCoord.orientation.getOpposite(), false)) {
			int id = world().getBlockId(x(), y(), z());
			Block gate = Block.blocksList[id];
			if (gate != null) {
				BasicUtils.dropItemFromLocation(world(), getItem(), false, null, getSide(), 10, new Coords(x(), y(), z()));
				tile().remPart(this);
			}
		}
	}

	private long toBitfield8(short[] a) {
		if (a.length > 8)
			throw new IllegalArgumentException("array too long");
		long rv = 0;
		for (int k = 0; k < a.length; k++) {
			if (a[k] < 0 || a[k] > 255) {
				throw new IllegalArgumentException("element out of range (index " + k + ", value " + a[k] + ")");
			}
			rv = (rv << 8) | a[k];
		}
		return rv;
	}

	private long toBitfield16(short[] a) {
		if (a.length > 4) {
			throw new IllegalArgumentException("array too long");
		}
		long rv = 0;
		for (int k = 0; k < a.length; k++)
			rv = (rv << 16) | a[k];
		return rv;
	}

	private void fromBitfield8(long bf, short[] a) {
		if (a.length > 8) {
			throw new IllegalArgumentException("array too long");
		}
		for (int k = a.length - 1; k >= 0; k--) {
			a[k] = (short) (bf & 255);
			bf >>= 8;
		}
	}

	private void fromBitfield16(long bf, short[] a) {
		if (a.length > 4) {
			throw new IllegalArgumentException("array too long");
		}
		for (int k = a.length - 1; k >= 0; k--) {
			a[k] = (short) bf;
			bf >>= 16;
		}
	}

	private void createLogic() {
		logic = type.createLogic();
		requiresTickUpdate = !(logic instanceof GateLogic.Stateless);
		if (logic instanceof GateLogic.WithPointer) {
			pointer = (GateLogic.WithPointer) logic;
		} else {
			pointer = null;
		}
		hasBundledConnections = logic instanceof GateLogic.WithBundledConnections;
	}

	@Deprecated
	private static int[] FLIPMAP_UNFLIPPED = new int[] { 0, 1, 2, 3 };

	@Deprecated
	private int relToAbsDirection(int rel) {
		return BasicWireUtils.dirMap[side][front][rel];
	}

	@Deprecated
	private int absToRelDirection(int abs) {
		if ((abs & 6) == (side & 6))
			return -1;

		int rel = BasicWireUtils.invDirMap[side][front][abs];
		return rel;
	}

	private short getInputValue(int rel) {
		int abs = relToAbsDirection(rel);
		if (hasBundledConnections && ((GateLogic.WithBundledConnections) logic).isBundledConnection(rel)) {
			return getBundledInputBitmask(abs);
		} else {
			return getInputStrength(abs);
		}
	}

	@Deprecated
	private short getBundledInputBitmask(int abs) {
		ForgeDirection fd = ForgeDirection.VALID_DIRECTIONS[abs];
		int x = x() + fd.offsetX, y = y() + fd.offsetY, z = z() + fd.offsetZ;
		TileEntity te = world().getBlockTileEntity(x, y, z);

		if (te instanceof IBundledEmitter) {
			byte[] values = ((IBundledEmitter) te).getBundledCableStrength(side, abs ^ 1);
			if (values == null)
				return 0;

			short rv = 0;
			for (int k = 15; k >= 0; k--) {
				rv <<= 1;
				if (values[k] != 0)
					rv |= 1;
			}

			return rv;
		}
		return 0;
	}

	@Deprecated
	private short getInputStrength(int abs) {
		// RedstoneInteractions.getPowerTo(this, Rotation.rotationTo(s1, s2))
		switch (abs) {
		case Dir.NX:
			return BasicWireUtils.getPowerStrength(world(), x() - 1, y(), z(), abs ^ 1, side);
		case Dir.PX:
			return BasicWireUtils.getPowerStrength(world(), x() + 1, y(), z(), abs ^ 1, side);
		case Dir.NY:
			return BasicWireUtils.getPowerStrength(world(), x(), y() - 1, z(), abs ^ 1, side);
		case Dir.PY:
			return BasicWireUtils.getPowerStrength(world(), x(), y() + 1, z(), abs ^ 1, side);
		case Dir.NZ:
			return BasicWireUtils.getPowerStrength(world(), x(), y(), z() - 1, abs ^ 1, side);
		case Dir.PZ:
			return BasicWireUtils.getPowerStrength(world(), x(), y(), z() + 1, abs ^ 1, side);
		}
		throw new IllegalArgumentException("Invalid direction " + abs);
	}

	@Deprecated
	public int getVanillaOutputStrength(int dir) {
		int rel = dir;// absToRelDirection(dir);
		if (rel < 0)
			return 0;

		if (hasBundledConnections && ((GateLogic.WithBundledConnections) logic).isBundledConnection(rel))
			return 0;

		return prevAbsOutputs[dir] / 17;
	}

	@Override
	public boolean connects(IWire wire, int blockFace, int fromDirection) {
		if (blockFace != side)
			return false;

		if (logic == null)
			return false;

		int rel = absToRelDirection(fromDirection);
		if (rel < 0)
			return false;

		boolean bundled = (hasBundledConnections && ((GateLogic.WithBundledConnections) logic).isBundledConnection(rel));

		if (!(bundled ? wire instanceof IBundledWire : wire instanceof IRedstoneWire))
			return false;

		return logic.connectsToDirection(rel);
	}

	@Override
	public boolean connectsAroundCorner(IWire wire, int blockFace, int fromDirection) {
		return false;
	}

	@Override
	@Deprecated
	public void scheduledTick() {
		updatePending = false;
		System.arraycopy(absOutputs, 0, prevAbsOutputs, 0, 6);
		System.arraycopy(outputs, 0, prevOutputs, 0, 4);
		updateRenderState();
		world().notifyBlocksOfNeighborChange(x(), y(), z(), ProjectRed.blockGate.blockID);
		world().notifyBlocksOfNeighborChange(x(), y() - 1, z(), ProjectRed.blockGate.blockID);
		world().notifyBlocksOfNeighborChange(x(), y() + 1, z(), ProjectRed.blockGate.blockID);
		world().notifyBlocksOfNeighborChange(x() - 1, y(), z(), ProjectRed.blockGate.blockID);
		world().notifyBlocksOfNeighborChange(x() + 1, y(), z(), ProjectRed.blockGate.blockID);
		world().notifyBlocksOfNeighborChange(x(), y(), z() - 1, ProjectRed.blockGate.blockID);
		world().notifyBlocksOfNeighborChange(x(), y(), z() + 1, ProjectRed.blockGate.blockID);

		if (hasBundledConnections) {
			for (int rel = 0; rel < 4; rel++) {
				if (!((GateLogic.WithBundledConnections) logic).isBundledConnection(rel)) {
					continue;
				}

				int abs = relToAbsDirection(rel);
				ForgeDirection fd = ForgeDirection.VALID_DIRECTIONS[abs];
				int x = x() + fd.offsetX, y = y() + fd.offsetY, z = z() + fd.offsetZ;

				TileEntity te = world().getBlockTileEntity(x, y, z);
				if (te != null && te instanceof IBundledUpdatable)
					((IBundledUpdatable) te).onBundledInputChanged();
			}
		}
	}

	// called when shift-clicked by a screwdriver
	public void configure() {
		if (BasicUtils.isServer(world())) {
			gateSettings = logic.configure(gateSettings);
			updateLogic(false, true);
			updateChange();
		}
	}

	// called when non-shift-clicked by a screwdriver
	public void rotate() {
		int relativeRotationIndex = Rotation.rotationTo(side, front);
		relativeRotationIndex++;
		if (relativeRotationIndex > 3) {
			relativeRotationIndex = 0;
		}
		front = (byte) (Rotation.rotateSide(side, relativeRotationIndex));
		if (BasicUtils.isServer(world())) {
			updateLogic(false, true);
			updateChange();
		}
	}

	@Override
	public float getStrength(MovingObjectPosition hit, EntityPlayer player) {
		return hit.sideHit == 1 ? 1.75f : 1.5f;
	}

	@Override
	public boolean activate(EntityPlayer player, MovingObjectPosition hit, ItemStack held) {
		if (held != null && held.getItem() == ProjectRed.itemScrewdriver) {
			if (player.isSneaking()) {
				this.configure();
				return true;
			} else {
				this.rotate();
				return true;
			}
		}

		if (world().isRemote) {
			return type != null && GateLogic.WithRightClickAction.class.isAssignableFrom(type.getLogicClass());
		}
		if (logic instanceof GateLogic.WithRightClickAction) {
			((GateLogic.WithRightClickAction) logic).onRightClick(player, this);
			return true;
		}
		return false;
	}

	private byte[] returnedBundledCableStrength;

	@Override
	public byte[] getBundledCableStrength(int blockFace, int toDirection) {
		if (!hasBundledConnections)
			return null;

		if (blockFace != side)
			return null;

		int rel = absToRelDirection(toDirection);
		if (rel < 0)
			return null;

		if (!((GateLogic.WithBundledConnections) logic).isBundledConnection(rel))
			return null;

		if (returnedBundledCableStrength == null)
			returnedBundledCableStrength = new byte[16];

		short bitmask = prevOutputs[rel];
		for (int k = 0; k < 16; k++) {
			returnedBundledCableStrength[k] = ((bitmask & 1) != 0) ? (byte) 255 : 0;
			bitmask >>= 1;
		}

		return returnedBundledCableStrength;
	}

	@Override
	public void onBundledInputChanged() {
		if (hasBundledConnections)
			updateLogic(false, false);
	}

	public ItemStack getItem() {
		return new ItemStack(ProjectRed.itemPartGate, 1, type.ordinal());
	}

	@Override
	public Iterable<ItemStack> getDrops() {
		return Arrays.asList(getItem());
	}

	@Override
	public Cuboid6 getBounds() {
		Cuboid6 base = new Cuboid6(0 / 8D, 0, 0 / 8D, 8 / 8D, 1 / 8D, 8 / 8D);
		return base.copy().transform(sideRotations[side].at(center));
	}

	@Override
	public String getType() {
		return "projred-gate";
	}

	@Override
	public int getSlotMask() {
		return 1 << side;
	}

	@Override
	public boolean solid(int side) {
		return false;
	}

	@Override
	public int redstoneConductionMap() {
		return 0;
	}

	@Override
	@SideOnly(Side.CLIENT)
	public void renderStatic(Vector3 pos, LazyLightMatrix olm, int pass) {
		if (pass == 0)
			GateStaticRenderer.instance.renderWorldBlock(this, (int) pos.x, (int) pos.y, (int) pos.z);
	}

	@Override
	public void renderDynamic(Vector3 pos, float frame, int pass) {
		if (pass == 0)
			GateDynamicRenderer.instance.renderGateWithTESR(this, pos.x, pos.y, pos.z);
	}

	@Override
	public int strongPowerLevel(int sideOut) {
		try {
			int ourRot = Rotation.rotationTo(side, front);
			int askedRot = Rotation.rotationTo(side, sideOut);
			int rotIndex = (Math.max(ourRot, askedRot) - Math.min(ourRot, askedRot));
			return outputs[rotIndex];
		} catch (Throwable t) {
			return 0;
		}
	}

	@Override
	public int weakPowerLevel(int side) {
		return strongPowerLevel(side);
	}

	@Override
	public boolean canConnectRedstone(int sideConnect) {
		if (type == null) {
			return false;
		}
		try {
			GateLogic l = type.getLogicClass().newInstance();
			int ourRot = Rotation.rotationTo(side, front);
			int askedRot = Rotation.rotationTo(side, sideConnect);
			int rotIndex = (Math.max(ourRot, askedRot) - Math.min(ourRot, askedRot));
			return l.connectsToDirection(rotIndex);
		} catch (Throwable t) {
			return false;
		}
	}

	@Override
	public int getFace() {
		return side;
	}

	/**
	 * Collision bounding boxes.
	 */
	@Override
	public Iterable<Cuboid6> getOcclusionBoxes() {
		return Arrays.asList(getBounds());
	}

	/**
	 * Selection bounding box.
	 */
	@Override
	public Iterable<IndexedCuboid6> getSubParts() {
		return Arrays.asList(new IndexedCuboid6(0, getBounds()));
	}

	/**
	 * Tests to see if the parts have colliding boxes, they cannot be placed.
	 */
	@Override
	public boolean occlusionTest(TMultiPart npart) {
		return NormalOcclusionTest.apply(this, npart);
	}

}