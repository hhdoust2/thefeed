package server

import (
	"crypto/rand"
	"fmt"
	"sync"
	"time"

	"github.com/sartoopjj/thefeed/internal/protocol"
)

// Feed manages the block data for all channels.
type Feed struct {
	mu         sync.RWMutex
	marker     [protocol.MarkerSize]byte
	channels   []string
	blocks     map[int][][]byte
	lastIDs    map[int]uint32
	metaBlocks [][]byte // cached metadata split into blocks
	updated    time.Time
}

// NewFeed creates a new Feed with the given channel names.
func NewFeed(channels []string) *Feed {
	f := &Feed{
		channels: channels,
		blocks:   make(map[int][][]byte),
		lastIDs:  make(map[int]uint32),
	}
	f.rotateMarker()
	f.rebuildMetaBlocks()
	return f
}

func (f *Feed) rotateMarker() {
	rand.Read(f.marker[:])
}

// UpdateChannel replaces the messages for a channel, re-serializing into blocks.
func (f *Feed) UpdateChannel(channelNum int, msgs []protocol.Message) {
	data := protocol.SerializeMessages(msgs)
	blocks := protocol.SplitIntoBlocks(data)

	var lastID uint32
	if len(msgs) > 0 {
		lastID = msgs[0].ID
	}

	f.mu.Lock()
	defer f.mu.Unlock()

	f.blocks[channelNum] = blocks
	f.lastIDs[channelNum] = lastID
	f.updated = time.Now()
	f.rotateMarker()
	f.rebuildMetaBlocks()
}

// GetBlock returns the block data for a given channel and block number.
func (f *Feed) GetBlock(channel, block int) ([]byte, error) {
	f.mu.RLock()
	defer f.mu.RUnlock()

	if channel == protocol.MetadataChannel {
		return f.getMetadataBlock(block)
	}

	ch, ok := f.blocks[channel]
	if !ok {
		return nil, fmt.Errorf("channel %d not found", channel)
	}
	if block < 0 || block >= len(ch) {
		return nil, fmt.Errorf("block %d out of range (channel %d has %d blocks)", block, channel, len(ch))
	}
	return ch[block], nil
}

func (f *Feed) getMetadataBlock(block int) ([]byte, error) {
	if len(f.metaBlocks) == 0 {
		f.rebuildMetaBlocks()
	}
	if block < 0 || block >= len(f.metaBlocks) {
		return nil, fmt.Errorf("metadata block %d out of range (%d blocks)", block, len(f.metaBlocks))
	}
	return f.metaBlocks[block], nil
}

// rebuildMetaBlocks re-serializes the metadata and splits it into blocks.
// Must be called with f.mu held.
func (f *Feed) rebuildMetaBlocks() {
	meta := &protocol.Metadata{
		Marker:    f.marker,
		Timestamp: uint32(time.Now().Unix()),
	}

	for i, name := range f.channels {
		chNum := i + 1
		blocks, ok := f.blocks[chNum]
		blockCount := uint16(0)
		if ok {
			blockCount = uint16(len(blocks))
		}
		meta.Channels = append(meta.Channels, protocol.ChannelInfo{
			Name:      name,
			Blocks:    blockCount,
			LastMsgID: f.lastIDs[chNum],
		})
	}

	data := protocol.SerializeMetadata(meta)
	f.metaBlocks = protocol.SplitIntoBlocks(data)
}

// ChannelNames returns the configured channel names.
func (f *Feed) ChannelNames() []string {
	f.mu.RLock()
	defer f.mu.RUnlock()
	result := make([]string, len(f.channels))
	copy(result, f.channels)
	return result
}
