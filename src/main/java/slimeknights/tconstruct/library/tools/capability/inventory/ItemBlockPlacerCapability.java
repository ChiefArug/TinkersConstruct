package slimeknights.tconstruct.library.tools.capability.inventory;

import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import slimeknights.tconstruct.TConstruct;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * A capability that provides blocks from items to things that use blocks, like the Exchanging modifier.
 * Providers of this capability should keep a reference to the stack provided from
 * and update it as needed.
 * @see ManualPlacement
 */
public interface ItemBlockPlacerCapability {

  /** Capability ID */
  ResourceLocation ID = TConstruct.getResource("block_provider");
  /** Capability type */
  Capability<ItemBlockPlacerCapability> CAPABILITY = CapabilityManager.get(new CapabilityToken<>() {});

  /** Registers this capability */
  static void register() {
    FMLJavaModLoadingContext.get().getModEventBus().addListener(EventPriority.NORMAL, false, RegisterCapabilitiesEvent.class, ItemBlockPlacerCapability::register);
    // receive the attach event on low priority, so that our default implementations do not override other mods.
    MinecraftForge.EVENT_BUS.addGenericListener(ItemStack.class, EventPriority.LOW, ItemBlockPlacerCapability::attachCapability);
  }

  /** Registers the capability with the event bus */
  private static void register(RegisterCapabilitiesEvent event) {
    event.register(ItemBlockPlacerCapability.class);
  }

  /** Event listener to attach the capability */
  private static void attachCapability(AttachCapabilitiesEvent<ItemStack> event) {
    if (event.getObject().getItem() instanceof BlockItem block) {
      event.addCapability(ID, new SimpleBlockItem(event.getObject(), block));
    }
  }

  /**
   * @return The block provider for this stack, or null if this stack cannot provide blocks
   */
  @SuppressWarnings("DataFlowIssue")
  @Nullable
  static ItemBlockPlacerCapability getBlockProvider(ItemStack stack) {
    return stack.getCapability(CAPABILITY).orElse(null);
  }

  /**
   * @return The {@link Block} that this provides, or {@code null} if this cannot provide a block (usually due to running out).
   */
  @Nullable
  Block getBlock();

  /**
   * Place the block that this contains.
   * @param forceReplace If a block already in the targeted position should be force replaced by this placement.
   * @return The result of the placement
   */
  InteractionResult place(Level level, @Nullable Player player, InteractionHand hand, BlockHitResult hitResult, boolean forceReplace);

  /**
   * A simple implementation of {@link ItemBlockPlacerCapability} that provides a wrapper around {@link BlockItem BlockItems}
   */
  final class SimpleBlockItem implements ItemBlockPlacerCapability, ICapabilityProvider {

    private final ItemStack stack;
    private final BlockItem contained;
    @Nullable
    private LazyOptional<ItemBlockPlacerCapability> lazy;

    public SimpleBlockItem(ItemStack stack, BlockItem contained) {
      this.stack = stack;
      this.contained = contained;
    }

    @Nullable
    @Override
    public Block getBlock() {
      return stack.isEmpty() ? null : contained.getBlock();
    }

    @Override
    public InteractionResult place(Level level, @Nullable Player player, InteractionHand hand, BlockHitResult hitResult, boolean forceReplace) {
      if (stack.isEmpty()) {
        return InteractionResult.FAIL;
      }

      BlockPlaceContext ctx = new BlockPlaceContext(level, player, hand, stack, hitResult);
      if (forceReplace) {
        ctx.replaceClicked = true;
      }

      return contained.place(ctx);
    }

    // Because this is an incredibly simple capability it acts as provider and as the actual capability implementation.
    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> cap, @Nullable Direction dir) {
      if (lazy == null)
        return CAPABILITY.orEmpty(cap, lazy = LazyOptional.of(() -> this));
      return CAPABILITY.orEmpty(cap, lazy);
    }
  }

  /**
   * An interface that implements a manual method of placement for when a {@link BlockItem} is not available.
   * <br>
   * Note for implementors: You should override {@link #place} and call super if you want to implement some resource being consumed.
   * Ensure to check {@link InteractionResult#consumesAction()} before consuming.
   */
  interface ManualPlacement extends ItemBlockPlacerCapability {

    /**
     * A fake stack used in some parts of the placing of the block as context.
     * It will not be mutated by our code.
     * Most notably passed to {@link Block#setPlacedBy}, which some mods use to add NBT data to BlockEntities after placement.
     */
    ItemStack getFakeStack();

    @Override
    default InteractionResult place(Level level, @org.jetbrains.annotations.Nullable Player player, InteractionHand hand, BlockHitResult hitResult, boolean forceReplace) {
      Block block = getBlock();
      if (block == null) {
        return InteractionResult.FAIL;
      }
      ItemStack fakeStack = getFakeStack();
      BlockPlaceContext placeContext = new BlockPlaceContext(level, player, hand, fakeStack, hitResult);
      if (forceReplace) {
        placeContext.replaceClicked = true;
      }
      BlockPos clicked = placeContext.getClickedPos();

      // following code is based on PlaceBlockFluidEffect logic, which in turn is based on block item, with notably differences of not calling block item methods

      BlockState state = block.getStateForPlacement(placeContext);
      if (state == null) {
        return InteractionResult.FAIL;
      }

      if (!state.canSurvive(level, clicked) || !level.isUnobstructed(state, clicked, player == null ? CollisionContext.empty() : CollisionContext.of(player))) {
        return InteractionResult.FAIL;
      }
      // actually place the block
      if (!level.setBlock(clicked, state, Block.UPDATE_ALL_IMMEDIATE)) {
        return InteractionResult.FAIL;
      }
      // if its the expected block, run some criteria stuffs
      BlockState placed = level.getBlockState(clicked);
      if (placed.is(block)) {
        // difference from BlockItem: do not update block state or block entity from tag as we have no tag
        // it might however be worth passing in a set of properties to set here as part of JSON
        // setPlacedBy only matters when placing from held item
        block.setPlacedBy(level, clicked, placed, player, fakeStack);
        if (player instanceof ServerPlayer serverPlayer) {
          CriteriaTriggers.PLACED_BLOCK.trigger(serverPlayer, clicked, fakeStack);
        }
      }

      return InteractionResult.sidedSuccess(level.isClientSide());
    };
  }
}
