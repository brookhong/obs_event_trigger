#
# Copyright (c) 2010 Anas Nashif, Intel Inc.
#
# This program is free software; you can redistribute it and/or modify
# it under the terms of the GNU General Public License version 2 as
# published by the Free Software Foundation.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program (see the file COPYING); if not, write to the
# Free Software Foundation, Inc.,
# 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA
#
################################################################
#
# Module to talk to RabbitMQ
#

package notify_rabbitmq;

use Net::RabbitMQ;
use BSConfig;
use JSON::XS;
use Data::Dumper;

use strict;

sub new {
  my $self = {};
  bless $self, shift;
  return $self;
}

sub notify() {
  my ($self, $type, $paramRef ) = @_;

  $type = "UNKNOWN" unless $type;
  my $prefix = $BSConfig::notification_namespace || "OBS";
  $type =  "${prefix}_$type";


  # crashed on 'OBS_SRCSRV_START'
  if ($type ne "${prefix}_SRCSRV_START" && $paramRef) {
    $paramRef->{'eventtype'} = $type;
    $paramRef->{'time'} = time();
    my $mq = Net::RabbitMQ->new();
    $mq->connect("172.28.117.23", { user => "mailer", password => "mailerpwd", vhost => "mailer_vhost" });
    warn("RabbitMQ Plugin: $@") if $@;
    $mq->channel_open(1);
    print Dumper($paramRef);
    $mq->publish(1, "mailer", encode_json($paramRef), { exchange => 'amq.topic' });
    $mq->disconnect();
  }


}

1;
